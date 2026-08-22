package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject

enum class InsertObjectType {
    TABLE,
    ATTACHMENT,
    IMAGE,
    SPREADSHEET,
    NOTE_LINK,
}

enum class ImageSourceKind {
    IMAGE,
    PDF_PAGE,
    STICKER,
}

enum class ImageImportDisplayMode(val label: String) {
    ASK("导入时询问"),
    INVERT("自动反色"),
    ORIGINAL("保持原图"),
}

sealed class InsertObject(
    open val id: String,
    open var documentX: Float,
    open var documentY: Float,
    open var width: Float,
    open var height: Float,
) {
    abstract val type: InsertObjectType

    data class Table(
        override val id: String,
        override var documentX: Float,
        override var documentY: Float,
        override var width: Float,
        override var height: Float,
        val rows: Int,
        val columns: Int,
        val cells: MutableList<String> = MutableList(rows * columns) { "" },
    ) : InsertObject(id, documentX, documentY, width, height) {
        override val type = InsertObjectType.TABLE
    }

    data class Attachment(
        override val id: String,
        override var documentX: Float,
        override var documentY: Float,
        override var width: Float,
        override var height: Float,
        val assetName: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
    ) : InsertObject(id, documentX, documentY, width, height) {
        override val type = InsertObjectType.ATTACHMENT
    }

    data class Image(
        override val id: String,
        override var documentX: Float,
        override var documentY: Float,
        override var width: Float,
        override var height: Float,
        val assetName: String,
        val displayName: String,
        val mimeType: String,
        val sourceKind: ImageSourceKind,
        val viewInverted: Boolean = false,
        val sourceAssetName: String? = null,
        val sourcePageNumber: Int? = null,
        val pageBackground: Boolean = false,
    ) : InsertObject(id, documentX, documentY, width, height) {
        override val type = InsertObjectType.IMAGE
    }

    data class Spreadsheet(
        override val id: String,
        override var documentX: Float,
        override var documentY: Float,
        override var width: Float,
        override var height: Float,
        val assetName: String,
        val displayName: String,
        val mimeType: String,
        val previewRows: MutableList<MutableList<String>>,
    ) : InsertObject(id, documentX, documentY, width, height) {
        override val type = InsertObjectType.SPREADSHEET
    }

    data class NoteLink(
        override val id: String,
        override var documentX: Float,
        override var documentY: Float,
        override var width: Float,
        override var height: Float,
        val targetNotebookId: String,
        val targetSectionId: String,
        val targetPageId: String,
        val targetTitle: String,
    ) : InsertObject(id, documentX, documentY, width, height) {
        override val type = InsertObjectType.NOTE_LINK
    }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("x", documentX.toDouble())
            put("y", documentY.toDouble())
            put("width", width.toDouble())
            put("height", height.toDouble())
            when (this@InsertObject) {
                is Table -> {
                    put("rows", rows)
                    put("columns", columns)
                    put("cells", JSONArray(cells))
                }
                is Attachment -> {
                    put("assetName", assetName)
                    put("displayName", displayName)
                    put("mimeType", mimeType)
                    put("sizeBytes", sizeBytes)
                }
                is Image -> {
                    put("assetName", assetName)
                    put("displayName", displayName)
                    put("mimeType", mimeType)
                    put("sourceKind", sourceKind.name)
                    put("viewInverted", viewInverted)
                    sourceAssetName?.let { put("sourceAssetName", it) }
                    sourcePageNumber?.let { put("sourcePageNumber", it) }
                    put("pageBackground", pageBackground)
                }
                is Spreadsheet -> {
                    put("assetName", assetName)
                    put("displayName", displayName)
                    put("mimeType", mimeType)
                    put(
                        "previewRows",
                        JSONArray().apply {
                            previewRows.forEach { row -> put(JSONArray(row)) }
                        },
                    )
                }
                is NoteLink -> {
                    put("targetNotebookId", targetNotebookId)
                    put("targetSectionId", targetSectionId)
                    put("targetPageId", targetPageId)
                    put("targetTitle", targetTitle)
                }
            }
        }

    companion object {
        fun fromJson(json: JSONObject): InsertObject {
            val id = json.getString("id")
            val x = json.getDouble("x").toFloat()
            val y = json.getDouble("y").toFloat()
            val width = json.getDouble("width").toFloat()
            val height = json.getDouble("height").toFloat()
            return when (InsertObjectType.valueOf(json.getString("type"))) {
                InsertObjectType.TABLE -> {
                    val rows = json.getInt("rows")
                    val columns = json.getInt("columns")
                    val source = json.optJSONArray("cells") ?: JSONArray()
                    Table(id, x, y, width, height, rows, columns, MutableList(rows * columns) { index -> source.optString(index) })
                }
                InsertObjectType.ATTACHMENT ->
                    Attachment(
                        id, x, y, width, height,
                        json.getString("assetName"), json.getString("displayName"),
                        json.optString("mimeType", "application/octet-stream"), json.optLong("sizeBytes", 0L),
                    )
                InsertObjectType.IMAGE ->
                    Image(
                        id, x, y, width, height,
                        json.getString("assetName"), json.optString("displayName"),
                        json.optString("mimeType", "image/png"),
                        ImageSourceKind.valueOf(json.optString("sourceKind", ImageSourceKind.IMAGE.name)),
                        json.optBoolean("viewInverted", false),
                        json.optString("sourceAssetName").takeIf { it.isNotEmpty() },
                        if (json.has("sourcePageNumber")) json.getInt("sourcePageNumber") else null,
                        json.optBoolean("pageBackground", false),
                    )
                InsertObjectType.SPREADSHEET -> {
                    val rows = mutableListOf<MutableList<String>>()
                    val jsonRows = json.optJSONArray("previewRows") ?: JSONArray()
                    for (rowIndex in 0 until jsonRows.length()) {
                        val jsonRow = jsonRows.optJSONArray(rowIndex) ?: continue
                        rows += MutableList(jsonRow.length()) { columnIndex -> jsonRow.optString(columnIndex) }
                    }
                    Spreadsheet(
                        id, x, y, width, height,
                        json.getString("assetName"), json.getString("displayName"),
                        json.optString("mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), rows,
                    )
                }
                InsertObjectType.NOTE_LINK ->
                    NoteLink(
                        id, x, y, width, height,
                        json.getString("targetNotebookId"), json.getString("targetSectionId"),
                        json.getString("targetPageId"), json.getString("targetTitle"),
                    )
            }
        }
    }
}

data class PageInsertDocument(
    val notebookId: String,
    val pageId: String,
    val objects: MutableList<InsertObject> = mutableListOf(),
) {
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("version", 1)
            put("notebookId", notebookId)
            put("pageId", pageId)
            put("objects", JSONArray().apply { objects.forEach { put(it.toJson()) } })
        }

    companion object {
        fun fromJson(json: JSONObject): PageInsertDocument {
            val document = PageInsertDocument(json.getString("notebookId"), json.getString("pageId"))
            val objects = json.optJSONArray("objects") ?: JSONArray()
            for (index in 0 until objects.length()) document.objects += InsertObject.fromJson(objects.getJSONObject(index))
            return document
        }
    }
}
