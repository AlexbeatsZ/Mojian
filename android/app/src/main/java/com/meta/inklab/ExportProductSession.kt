package com.meta.inklab

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.ink.strokes.Stroke
import org.json.JSONObject
import java.util.concurrent.CancellationException

data class ExportPageIdentity(val id: String, val title: String, val settings: PageViewSettings,
    val originalSize: PdfOutputPageSize?)

class ExportProductSession(private val context: Context, private val repository: PageContentRepository,
    private val notebookId: String, private val pages: List<ExportPageIdentity>) : ExportPageProvider {
    @Volatile private var cancelled = false
    private val summaries = mutableMapOf<Int, ExportSourceSummary>()
    private val versions = mutableMapOf<Int, Pair<Long, Long>>()
    private val ui = ExportUiDispatcher()
    val isCancelled get() = cancelled

    fun cancel() { cancelled = true; synchronized(summaries) { summaries.clear(); versions.clear() } }
    private fun checkActive() { if (cancelled) throw CancellationException() }

    override fun plan(options: ExportOptions, outputSize: PdfOutputPageSize?): ExportPlan {
        checkActive()
        require(pages.isNotEmpty()) { "当前范围没有页面" }
        return ExportPlan.create(pages.size, options) { summary(it).sheets(options, outputSize) }
    }

    fun images(): Pair<Boolean, List<ExportImageChoice>> {
        checkActive()
        return pages.any { it.settings.darkBackground } to pages.indices
            .filter { pages[it].settings.darkBackground }.flatMap { summary(it).images }
    }

    private fun summary(index: Int): ExportSourceSummary {
        checkActive()
        synchronized(summaries) { summaries[index] }?.let { return it }
        val identity = pages[index]
        val file=repository.pageFile(notebookId,identity.id)
        val version=file.lastModified() to file.length()
        val document = repository.load(notebookId, identity.id)
        val bounds = mutableListOf<StrokeViewportBounds>()
        repeat(document.strokes.length()) { strokeIndex ->
            checkActive()
            val json = document.strokes.getJSONObject(strokeIndex)
            run {
                val stroke=SharedStrokeCodec.decode(json,{ it.argb })
                SharedStrokeCodec.bounds(stroke)?.let { bounds += it } ?: run {
                    val inputs=stroke.inputs
                    if(!inputs.isEmpty()) {
                        var left=Float.POSITIVE_INFINITY; var top=Float.POSITIVE_INFINITY
                        var right=Float.NEGATIVE_INFINITY; var bottom=Float.NEGATIVE_INFINITY
                        repeat(inputs.size) { pointIndex ->
                            val point=inputs.get(pointIndex)
                            left=minOf(left,point.x); top=minOf(top,point.y)
                            right=maxOf(right,point.x); bottom=maxOf(bottom,point.y)
                        }
                        val margin=stroke.brush.size/2
                        bounds += StrokeViewportBounds(left-margin,top-margin,right+margin,bottom+margin)
                    }
                }
            }
        }
        bounds += document.objects.map { StrokeViewportBounds(it.documentX,it.documentY,it.documentX+it.width,it.documentY+it.height) }
        val halfSize = 14f*context.resources.displayMetrics.density
        bounds += SharedContentCodec.tags(document).map { StrokeViewportBounds(it.documentX-halfSize,it.documentY-halfSize,it.documentX+halfSize,it.documentY+halfSize) }
        val snapshots = SharedContentCodec.readText(document)
        if (snapshots.isNotEmpty()) bounds += ui.call {
            checkActive()
            val text = textLayer(identity.settings, snapshots)
            try { text.contentBounds().toList() } finally { text.replaceEditors(emptyList()) }
        }
        val images=document.objects.filterIsInstance<InsertObject.Image>().mapIndexed { imageIndex,image ->
            ExportImageChoice(image.id,"${identity.title} · 图片 ${imageIndex+1} · ${image.displayName}",image.viewInverted)
        }
        val result=ExportSourceSummary(identity.settings.copy(),bounds,identity.originalSize,images)
        check(version == (file.lastModified() to file.length())) { "页面正在更新，请重新打开导出" }
        synchronized(summaries) { checkActive(); summaries[index]=result; versions[index]=version }
        return result
    }

    private fun textLayer(settings: PageViewSettings, snapshots: List<CanvasTextSnapshot>) =
        CanvasTextInputLayer(context, ViewportState()).apply {
            setDarkBackground(settings.darkBackground)
            replaceEditors(snapshots)
            val density=context.resources.displayMetrics.density
            measure(View.MeasureSpec.makeMeasureSpec((900*density).toInt(),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((1200*density).toInt(),View.MeasureSpec.EXACTLY))
            layout(0,0,measuredWidth,measuredHeight)
        }

    override fun acquire(sourceIndex: Int): ExportPageLease {
        checkActive()
        val identity=pages[sourceIndex]
        val content=summary(sourceIndex).content
        val file=repository.pageFile(notebookId,identity.id)
        val version=synchronized(summaries) { versions[sourceIndex] }
        check(version == (file.lastModified() to file.length())) { "页面已更新，请重新打开导出" }
        val document=repository.load(notebookId,identity.id)
        val strokes=(0 until document.strokes.length()).map { index ->
            checkActive()
            val json=document.strokes.getJSONObject(index)
            SharedStrokeCodec.decode(json, { color ->
                if(color==InkPalette.BLACK && identity.settings.darkBackground) Color.rgb(239,239,239) else color.argb
            },SharedSceneOrder.visible(document.sharedSource,json))
        }
        val tags=SharedContentCodec.tags(document)
        val snapshots=SharedContentCodec.readText(document)
        check(version == (file.lastModified() to file.length())) { "页面正在更新，请重新打开导出" }
        val source=document.sharedSource
        val objects=SharedWorkspaceCodec.items(source.optJSONArray("objects")).associateBy { it.optString("id") }
        val textItems=SharedWorkspaceCodec.items(source.optJSONArray("textItems")).associateBy { it.optString("id") }
        val entries=mutableListOf<SharedSceneComposer.Item<Any>>()
        document.objects.forEach { item -> entries += SharedSceneComposer.Item(item,objects[item.id]
            ?: JSONObject().put("isPageBackground",item is InsertObject.Image && item.pageBackground)) }
        strokes.forEach { stroke -> entries += SharedSceneComposer.Item(stroke,SharedStrokeCodec.metadata(stroke) ?: JSONObject(),SharedStrokeCodec.visible(stroke)) }
        snapshots.forEach { item -> entries += SharedSceneComposer.Item(item.id,textItems[item.id] ?: JSONObject()) }
        val ordered=SharedSceneComposer.ordered(source,entries)
        return ui.call {
            checkActive()
            val viewport=ViewportState()
            val ink=DryInkView(context).apply {
                configureOutputPage(identity.settings,identity.title)
                setDarkBackground(identity.settings.darkBackground)
                replaceStrokes(strokes)
            }
            val inserts=InsertContentLayer(context,viewport,repository).apply { setDarkBackground(identity.settings.darkBackground); bind(document) }
            val tagLayer=CanvasTagLayer(context,viewport).apply { replaceTags(tags) }
            val text=textLayer(identity.settings,snapshots)
            var renderer: VectorPdfSource?=VectorPdfSource(identity.settings,content,identity.originalSize) { canvas,matrix,options ->
                checkActive()
                val output=OutputAppearance(options.printing,options.invertedImageIds,options.hideBackgroundFormat)
                ink.renderBackgroundForExport(canvas,matrix,output)
                ordered.forEach { item -> when(item) {
                    is InsertObject -> inserts.renderSceneObject(canvas,item,matrix,output)
                    is Stroke -> ink.renderVectorInkForExport(canvas,matrix,output=output,onlyStroke=item)
                    is String -> text.renderForExport(canvas,matrix,output=output,onlyId=item)
                } }
                tagLayer.renderForExport(canvas,matrix,output=output)
            }
            object : ExportPageLease {
                override val source get()=checkNotNull(renderer)
                override fun close() {
                    ui.call {
                        renderer=null
                        ink.replaceStrokes(emptyList())
                        inserts.bind(null)
                        tagLayer.replaceTags(emptyList())
                        text.replaceEditors(emptyList())
                    }
                }
            }
        }
    }
}
