package com.meta.inklab

import android.graphics.Color
import android.graphics.Typeface
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object SharedContentCodec {
    private val unit = SharedWorkspaceCodec.TO_SHARED
    private val restoredText = WeakIdentityCache<PageInsertDocument,Map<String,CanvasTextSnapshot>>()
    fun tags(document: PageInsertDocument): List<CanvasTagSnapshot> = SharedWorkspaceCodec.items(document.sharedSource.optJSONArray("tags")).map { value ->
        CanvasTagSnapshot(CanvasTagKind.entries[value.optInt("kind").coerceIn(0,1)],(value.getDouble("documentX")/unit).toFloat(),
            (value.getDouble("documentY")/unit).toFloat(),value.getString("id"))
    }
    fun text(document: PageInsertDocument): List<CanvasTextSnapshot> = readText(document).also {
        restoredText[document] = it.associateBy { item -> item.id }
    }
    fun readText(document: PageInsertDocument): List<CanvasTextSnapshot> = SharedWorkspaceCodec.items(document.sharedSource.optJSONArray("textItems")).map { value ->
        val mobileText=value.optString("androidRichText").takeIf(String::isNotBlank)?.let { Html.fromHtml(it,Html.FROM_HTML_MODE_COMPACT) }
        val basis=value.optJSONObject("androidRichTextCanonical")
        val compatible = mobileText!=null && mobileText.toString().trimEnd('\n')==value.optString("text").trimEnd('\n') &&
            (basis==null || listOf("fontSize","bold","italic","underline","foregroundHex","lineSpacing").all { key->basis.opt(key)==value.opt(key) })
        val text = if(compatible) SpannableString(requireNotNull(mobileText).subSequence(0,minOf(mobileText.length,value.optString("text").length))) else
            SpannableString(value.optString("text")).apply {
                if(isNotEmpty()) {
                    setSpan(AbsoluteSizeSpan((value.optDouble("fontSize",16.0)/unit).roundToInt(),false),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val style = (if(value.optBoolean("bold")) Typeface.BOLD else 0) or (if(value.optBoolean("italic")) Typeface.ITALIC else 0)
                    if(style!=0) setSpan(StyleSpan(style),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if(value.optBoolean("underline")) setSpan(UnderlineSpan(),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    value.optString("foregroundHex").takeIf(String::isNotBlank)?.let { setSpan(ForegroundColorSpan(Color.parseColor(it)),0,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
            }
        CanvasTextSnapshot(text,(value.optDouble("x")/unit).toFloat(),(value.optDouble("y")/unit).toFloat(),
            (value.optDouble("width",300.0)/unit).roundToInt().coerceAtLeast(1),(value.optDouble("height",50.0)/unit).roundToInt().coerceAtLeast(1),
            if(compatible) value.optDouble("androidContentScale",1.0).toFloat() else 1f,0f,value.optDouble("lineSpacing",1.0).toFloat(),value.getString("id"))
    }
    fun save(document: PageInsertDocument,tags: List<CanvasTagSnapshot>,text: List<CanvasTextSnapshot>,density: Float = 1f) {
        val source = document.sharedSource
        fun indexed(key: String): Map<String,JSONObject> = buildMap {
            SharedWorkspaceCodec.items(source.optJSONArray(key)).forEach { value ->
                val id = value.optString("id")
                if (!containsKey(id)) put(id,value)
            }
        }
        val previousTags = indexed("tags")
        val previousText = indexed("textItems")
        val restoredItems = restoredText[document]
        source.put("tags",JSONArray().apply { tags.forEach { tag ->
            val old=previousTags[tag.id]?.let(SharedWorkspaceCodec::copy) ?: JSONObject()
            val x=tag.documentX*unit; val y=tag.documentY*unit
            if(!old.has("documentX") || kotlin.math.abs(old.getDouble("documentX")-x)>.001) old.put("documentX",x)
            if(!old.has("documentY") || kotlin.math.abs(old.getDouble("documentY")-y)>.001) old.put("documentY",y)
            put(old.put("id",tag.id).put("kind",tag.kind.ordinal).apply { if(!has("scale")) put("scale",1) })
        } })
        source.put("textItems",JSONArray().apply { text.forEach { item ->
            val prior = previousText[item.id]?.let(SharedWorkspaceCodec::copy) ?: JSONObject()
            val restored = restoredItems?.get(item.id)
            fun html(value: CharSequence) = Html.toHtml(SpannableString(value),Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
            val itemHtml = html(item.text)
            val richTextChanged = restored==null || restored.contentScale!=item.contentScale || html(restored.text)!=itemHtml
            val lineSpacingChanged = restored==null || restored.lineSpacingMultiplier!=item.lineSpacingMultiplier
            if(restored!=null && kotlin.math.abs(restored.documentX-item.documentX)<.001f && kotlin.math.abs(restored.documentY-item.documentY)<.001f &&
                restored.width==item.width && restored.height==item.height && !richTextChanged && !lineSpacingChanged) {
                put(prior); return@forEach
            }
            val value = SharedWorkspaceCodec.copy(prior).put("id",item.id).put("x",item.documentX*unit).put("y",item.documentY*unit)
                .put("width",item.width*unit).put("height",item.height*unit).put("text",item.text.toString())
            if (lineSpacingChanged) {
                value.put("lineSpacing",item.lineSpacingMultiplier)
                value.optJSONObject("androidRichTextCanonical")?.put("lineSpacing",item.lineSpacingMultiplier)
            }
            if (richTextChanged) {
                value.put("androidRichText",itemHtml)
                    .put("androidContentScale",item.contentScale).put("lineSpacing",item.lineSpacingMultiplier)
                if(!value.has("fontSize")) value.put("fontSize",16.0)
                val spanned=item.text as? Spanned
                val style=spanned?.getSpans(0,item.text.length,StyleSpan::class.java)?.lastOrNull()?.style ?: 0
                value.put("bold",style and Typeface.BOLD!=0).put("italic",style and Typeface.ITALIC!=0)
                    .put("underline",spanned?.getSpans(0,item.text.length,UnderlineSpan::class.java)?.isNotEmpty() == true)
                spanned?.getSpans(0,item.text.length,AbsoluteSizeSpan::class.java)?.lastOrNull()?.let { size->value.put("fontSize",size.size*(if(size.dip) density else 1f)*unit*item.contentScale) }
                spanned?.getSpans(0,item.text.length,ForegroundColorSpan::class.java)?.lastOrNull()?.let { color->value.put("foregroundHex",SharedWorkspaceCodec.hex(color.foregroundColor)) }
                value.put("androidRichTextCanonical",JSONObject().apply { listOf("fontSize","bold","italic","underline","foregroundHex","lineSpacing").forEach { key->if(value.has(key)) put(key,value.get(key)) } })
            }
            put(value)
        } })
        restoredText[document]=text.associateBy { it.id }
    }
}
