package com.meta.inklab

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only acceptance: reads real received notes; writes only explicitly named disposable pages. */
class SyncProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val result=JSONObject()
        val started=android.os.SystemClock.elapsedRealtime()
        fun progress(phase: String) {
            result.put("phase",phase).put("elapsedMillis",android.os.SystemClock.elapsedRealtime()-started)
            File(cacheDir,"sync-probe.json").writeText(result.toString())
        }
        try {
            progress("library")
            val root=File("/storage/emulated/0/Documents/Mojian")
            val repo=PageContentRepository(root)
            val library=repo.loadLibrary()
            val pages=library.notebooks.sumOf { notebook->notebook.allSections().sumOf { it.pages.size } }
            result.put("notebooks",library.notebooks.size).put("pages",pages)
                .put("notebookIds",JSONArray(library.notebooks.map { it.id }))
            val requested= intent.getStringExtra("probeNotebook")
            if(requested!=null) {
                val writing=intent.getBooleanExtra("writeStroke",false)
                require(!writing || requested.startsWith("sync-probe-"))
                val pageId=requireNotNull(intent.getStringExtra("probePage"))
                require(!writing || pageId.startsWith("sync-probe-"))
                progress("page")
                val document=repo.load(requested,pageId)
                val loaded=android.os.SystemClock.elapsedRealtime()
                progress("native strokes")
                val strokes=SharedWorkspaceCodec.items(document.strokes).map { SharedStrokeCodec.decode(it,displayColor={ palette->palette.argb }) }
                val decoded=android.os.SystemClock.elapsedRealtime()
                progress("render")
                if(writing) {
                    val inputs=MutableStrokeInputBatch().apply {
                        add(InputToolType.STYLUS,100f,100f,0L,pressure=.3f)
                        add(InputToolType.STYLUS,220f,140f,12L,pressure=.8f)
                        add(InputToolType.STYLUS,320f,90f,24L,pressure=.5f)
                    }
                    val stroke=Stroke(BrushProfiles.create(.4f*DOCUMENT_UNITS_PER_MM,colorArgb=Color.RED),inputs)
                    document.strokes.put(SharedStrokeCodec.encode(stroke));repo.save(document)
                }
                val reopened=repo.load(requested,pageId)
                result.put("probeStrokes",reopened.strokes.length()).put("probeRevision",reopened.sharedSource.optLong("revision"))
                result.put("probePoints",SharedWorkspaceCodec.items(reopened.strokes).sumOf { it.getJSONArray("points").length() })
                val view=DryInkView(this).apply { backgroundFormat=CanvasBackgroundFormat.BLANK;replaceStrokes(strokes);layout(0,0,1200,900) }
                val bitmap=Bitmap.createBitmap(1200,900,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap));File(cacheDir,"sync-probe.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                result.put("loadMillis",loaded-started).put("decodeMillis",decoded-loaded)
                    .put("renderMillis",android.os.SystemClock.elapsedRealtime()-decoded)
            }
            // Exercise real native authoring, canonical outline/pressure/ID round trip and transform.
            val inputs=MutableStrokeInputBatch().apply { add(InputToolType.STYLUS,100f,100f,0L,pressure=.2f);add(InputToolType.STYLUS,350f,230f,8L,pressure=.8f) }
            val original=Stroke(BrushProfiles.create(.4f*DOCUMENT_UNITS_PER_MM,colorArgb=Color.BLUE),inputs)
            val json=SharedStrokeCodec.encode(original)
            val restored=SharedStrokeCodec.decode(json,displayColor={ it.argb })
            check(SharedStrokeCodec.encode(restored).toString()==json.toString())
            check(json.getJSONArray("points").getJSONObject(0).getDouble("pressure")<.3)
            val view=DryInkView(this).apply { backgroundFormat=CanvasBackgroundFormat.BLANK;layout(0,0,1200,900);replaceStrokes(listOf(restored)) }
            val bitmap=Bitmap.createBitmap(1200,900,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val pixels=IntArray(1200*900);bitmap.getPixels(pixels,0,1200,0,0,1200,900)
            check(pixels.any { Color.blue(it)>120 && Color.alpha(it)>50 }) { "Restored outline did not render" };bitmap.recycle()
            view.selectAll();view.translateSelection(40f,60f)
            val moved=SharedStrokeCodec.encode(view.snapshotStrokes().single())
            check(moved.getString("id")==json.getString("id"))
            check(kotlin.math.abs(moved.getJSONArray("points").getJSONObject(0).getDouble("x")-json.getJSONArray("points").getJSONObject(0).getDouble("x")-40*SharedWorkspaceCodec.TO_SHARED)<.001)
            result.put("nativeChecks","outline render; pressure/ID round trip; move retains source geometry").put("status","passed")
            val textDocument=PageInsertDocument("probe","text",sharedSource=JSONObject().put("textItems",JSONArray().put(
                JSONObject().put("id","text").put("text","first\r\nsecond").put("x",10).put("y",20).put("width",320).put("height",80).put("fontSize",16).put("bold",true))))
            val textSource=textDocument.sharedSource.toString()
            var restoredCallbacks=0
            val textLayer=CanvasTextInputLayer(this,ViewportState()).apply {
                onContentChanged={ restoredCallbacks++ }
                replaceEditors(SharedContentCodec.text(textDocument))
                measure(android.view.View.MeasureSpec.makeMeasureSpec(3000,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1800,android.view.View.MeasureSpec.EXACTLY))
                layout(0,0,measuredWidth,measuredHeight)
            }
            check(restoredCallbacks==0) { "Reading text marked the page dirty" }
            SharedContentCodec.save(textDocument,emptyList(),textLayer.snapshotEditors())
            check(textDocument.sharedSource.getJSONArray("textItems").getJSONObject(0).getString("text")=="first\r\nsecond") { "Reading text changed its newlines" }
            check(textDocument.sharedSource.getJSONArray("textItems").getJSONObject(0).getDouble("fontSize")==16.0)
            result.put("restoredTextChecks","zero save callbacks; original CRLF and font size retained")
        } catch(error: Throwable) { result.put("status","failed").put("error",error.toString());android.util.Log.e("MojianSyncProbe","Failure",error) }
        result.put("elapsedMillis",android.os.SystemClock.elapsedRealtime()-started)
        File(cacheDir,"sync-probe.json").writeText(result.toString())
        android.util.Log.i("MojianSyncProbe",result.toString());finish()
    }
}
