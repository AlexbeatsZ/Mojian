package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

/** The on-disk contract is the desktop v2 schema, in 96-DPI document units. */
object SharedWorkspaceCodec {
    const val TO_SHARED = (96.0 / 25.4) / DOCUMENT_UNITS_PER_MM
    val backgrounds = listOf(CanvasBackgroundFormat.BLANK, CanvasBackgroundFormat.RULE_NARROW,
        CanvasBackgroundFormat.RULE_STANDARD, CanvasBackgroundFormat.RULE_WIDE, CanvasBackgroundFormat.GRID_DENSE,
        CanvasBackgroundFormat.GRID_STANDARD, CanvasBackgroundFormat.GRID_WIDE, CanvasBackgroundFormat.DOTS_DENSE,
        CanvasBackgroundFormat.DOTS_STANDARD, CanvasBackgroundFormat.DOTS_WIDE, CanvasBackgroundFormat.RULE_TWO_COLUMN_STANDARD,
        CanvasBackgroundFormat.RULE_TWO_COLUMN_NARROW, CanvasBackgroundFormat.RULE_TWO_COLUMN_WIDE, CanvasBackgroundFormat.RULE_CLEAN)

    fun copy(json: JSONObject) = JSONObject(json.toString())
    fun items(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until array.length()).map { array.getJSONObject(it) }
    private fun array(values: List<JSONObject>) = JSONArray().apply { values.forEach(::put) }
    private fun matched(source: JSONArray?, id: String) = items(source).firstOrNull { it.optString("id") == id }

    fun decodeLibrary(source: JSONObject): NotebookLibrary {
        require(source.getInt("version") in 1..2) { "Unsupported workspace version" }
        val android = copy(source)
        items(android.getJSONArray("notebooks")).forEach { notebook ->
            notebook.put("pagePaneMode", if (notebook.optInt("pagePaneMode") == 1) "RECURSIVE" else "CURRENT_SECTION")
            visitPages(notebook) { page ->
                val appearance = page.getJSONObject("appearance")
                page.put("viewSettings", decodeAppearance(appearance).toJson())
                if (page.optBoolean("isPdfPage")) {
                    page.put("pdfPage", JSONObject().put("sourceOwnerPageId", page.getString("id"))
                        .put("sourceAssetName", page.optString("pdfSourceAssetName"))
                        .put("renderedAssetName", "").put("sourceDocumentName", page.optString("pdfSourceDocumentName"))
                        .put("pageNumber", page.optInt("pdfPageNumber", 1)).put("pageCount", page.optInt("pdfPageCount", 1))
                        .put("widthPoints", page.optDouble("pdfWidthPoints", 595.0)).put("heightPoints", page.optDouble("pdfHeightPoints", 842.0)))
                }
            }
        }
        return requireNotNull(NotebookLibrary.fromJson(android.toString())) { "Invalid workspace library" }
    }

    fun encodeLibrary(library: NotebookLibrary, original: JSONObject): JSONObject {
        val android = JSONObject(library.toJsonString())
        val result = copy(original).put("version", original.optInt("version",2))
        fun sections(current: JSONArray?, old: JSONArray?): JSONArray = array(items(current).map { node ->
            val previous = matched(old, node.getString("id")) ?: JSONObject()
            copy(previous).put("id", node.getString("id")).put("title", node.getString("title"))
                .put("expanded", previous.optBoolean("expanded", true))
                .put("children", sections(node.optJSONArray("children"), previous.optJSONArray("children")))
                .put("pages", array(items(node.optJSONArray("pages")).map { page ->
                    val prior = matched(previous.optJSONArray("pages"), page.getString("id")) ?: JSONObject()
                    copy(prior).put("id", page.getString("id")).put("title", page.getString("title"))
                        .put("createdAt", prior.optString("createdAt", java.time.Instant.now().toString()))
                        .put("updatedAt", prior.optString("updatedAt", java.time.Instant.now().toString()))
                        .put("appearance", encodeAppearance(PageViewSettings.fromJson(page.getJSONObject("viewSettings")), prior.optJSONObject("appearance")))
                        .apply {
                            page.optJSONObject("pdfPage")?.let { pdf ->
                                put("isPdfPage", true); put("pdfPageNumber", pdf.getInt("pageNumber")); put("pdfPageCount", pdf.getInt("pageCount"))
                                put("pdfWidthPoints", pdf.getDouble("widthPoints")); put("pdfHeightPoints", pdf.getDouble("heightPoints"))
                                put("pdfSourceAssetName", pdf.getString("sourceAssetName")); put("pdfSourceDocumentName", pdf.getString("sourceDocumentName"))
                            }
                        }
                }))
        })
        result.put("notebooks", array(items(android.getJSONArray("notebooks")).map { notebook ->
            val old = matched(original.optJSONArray("notebooks"), notebook.getString("id")) ?: JSONObject()
            copy(old).put("id", notebook.getString("id")).put("title", notebook.getString("title"))
                .put("sections", sections(notebook.optJSONArray("sections"), old.optJSONArray("sections")))
        }))
        if (!result.has("activeNotebookId")) result.put("activeNotebookId", library.activeNotebookId)
        return result
    }

    private fun visitPages(notebook: JSONObject, action: (JSONObject) -> Unit) {
        fun visit(nodes: JSONArray?) { items(nodes).forEach { node -> items(node.optJSONArray("pages")).forEach(action); visit(node.optJSONArray("children")) } }
        visit(notebook.optJSONArray("sections"))
    }

    fun decodeAppearance(json: JSONObject): PageViewSettings {
        val dark = json.optInt("theme") == 0
        val color = json.optString("pageColor", if (dark) "#1B1B1B" else "#FAFAFA")
        val palette = PageColor.entries.firstOrNull { hex(it.displayArgb(dark)).equals(color, true) } ?: PageColor.WHITE
        return PageViewSettings(paperSize = PaperSize.entries[json.optInt("paperKind").coerceIn(0,2)], pageColor = palette,
            backgroundFormat = backgrounds[json.optInt("background").coerceIn(0,backgrounds.lastIndex)], pageTitleVisible = json.optBoolean("showTitle", true),
            darkBackground = dark, a4PageCount = json.optInt("sheetCount",1).coerceAtLeast(1),
            sourcePageColor = color.removePrefix("#").takeIf { it.length == 6 || it.length == 8 }?.toLongOrNull(16)?.toInt()
                ?.let { if (color.length == 7) it or -0x1000000 else it },
            customDocumentWidth = (json.optDouble("width",793.7)/TO_SHARED).toFloat(),
            customDocumentHeight = (json.optDouble("height",1122.5)/TO_SHARED).toFloat(),
            boundlessDocumentWidth = (json.optDouble("infiniteWidth",793.7)/TO_SHARED).toFloat(),
            boundlessDocumentHeight = (json.optDouble("infiniteHeight",1122.5)/TO_SHARED).toFloat())
    }

    fun encodeAppearance(settings: PageViewSettings, source: JSONObject?): JSONObject {
        val result = source?.let(::copy) ?: JSONObject()
        val old = source?.let(::decodeAppearance)
        result.put("paperKind", settings.paperSize.ordinal).put("theme", if (settings.darkBackground) 0 else 1)
            .put("background", backgrounds.indexOf(settings.backgroundFormat)).put("showTitle",settings.pageTitleVisible).put("sheetCount",settings.a4PageCount)
        if (old == null || old.pageColor != settings.pageColor || old.darkBackground != settings.darkBackground)
            result.put("pageColor",hex(settings.pageColor.displayArgb(settings.darkBackground)))
        fun dimension(key: String, value: Float) {
            if (!result.has(key) || (result.getDouble(key)/TO_SHARED).toFloat()!=value) result.put(key,value*TO_SHARED)
        }
        dimension("width",settings.customDocumentWidth ?: PaperSize.A4.documentWidth!!)
        dimension("height",settings.customDocumentHeight ?: PaperSize.A4.documentHeight!!)
        dimension("infiniteWidth",settings.boundlessDocumentWidth); dimension("infiniteHeight",settings.boundlessDocumentHeight)
        return result
    }
    fun hex(argb: Int) = "#%06X".format(argb and 0xffffff)

    fun decodeObject(json: JSONObject): InsertObject {
        val value = copy(json)
        val kind = json.getInt("kind")
        value.put("type", when(kind) { 0->"TABLE"; 1->"ATTACHMENT"; 2,3,5->"IMAGE"; 4->"SPREADSHEET"; 6->"NOTE_LINK"; else->error("Unsupported object kind $kind") })
        listOf("x","y","width","height").forEach { key -> value.put(key,json.getDouble(key)/TO_SHARED) }
        value.put("sourceKind", when(kind) { 3->"PDF_PAGE";5->"STICKER";else->"IMAGE" })
        value.put("pageBackground",json.optBoolean("isPageBackground"))
        value.put("targetTitle",json.optString("displayName"))
        return InsertObject.fromJson(value)
    }
    fun encodeObject(item: InsertObject, original: JSONObject?): JSONObject {
        if(original!=null && decodeObject(original).toJson().toString()==item.toJson().toString()) return original
        val result = original?.let(::copy) ?: JSONObject()
        val android = item.toJson()
        android.keys().forEach { key -> if (key !in setOf("type","sourceKind","pageBackground","targetTitle")) result.put(key,android.get(key)) }
        result.put("kind", when(item) { is InsertObject.Table->0; is InsertObject.Attachment->1; is InsertObject.Image->when(item.sourceKind) {ImageSourceKind.IMAGE->2;ImageSourceKind.PDF_PAGE->3;ImageSourceKind.STICKER->5};is InsertObject.Spreadsheet->4;is InsertObject.NoteLink->6 })
        listOf("x","y","width","height").forEach { key ->
            val scaled = android.getDouble(key)*TO_SHARED
            result.put(key, if (original != null && kotlin.math.abs(original.optDouble(key)-scaled)<.001) original.get(key) else scaled)
        }
        if (item is InsertObject.Image) result.put("isPageBackground",item.pageBackground)
        if (item is InsertObject.NoteLink) result.put("displayName",item.targetTitle)
        return result
    }
    fun decodeDocument(source: JSONObject): PageInsertDocument {
        val canonical = source.optInt("version") >= 2 || items(source.optJSONArray("objects")).any { it.has("kind") }
        if (!canonical) return PageInsertDocument.fromJson(source)
        require(source.optInt("version") <= 2) { "Unsupported page version" }
        return PageInsertDocument(source.getString("notebookId"),source.getString("pageId"),
            items(source.optJSONArray("objects")).mapTo(mutableListOf(),::decodeObject),source.optJSONArray("strokes") ?: JSONArray(),source)
    }
    fun encodeDocument(document: PageInsertDocument): JSONObject {
        val source = document.sharedSource
        val result=JSONObject().apply { source.keys().forEach { key -> put(key,source.get(key)) } }
        return result.put("version",2).put("notebookId",document.notebookId).put("pageId",document.pageId)
            .put("objects",array(document.objects.map { encodeObject(it,matched(source.optJSONArray("objects"),it.id)) }))
            .put("strokes",document.strokes)
    }
}
