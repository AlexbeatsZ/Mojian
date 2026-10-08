package com.meta.inklab

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.geometry.MutableVec
import android.graphics.Color
import java.util.Locale
import java.util.UUID

/** Immutable native ink is kept separately from the external PNG/SVG clipboard formats. */
object SelectionClipboard {
    const val NATIVE_MIME = "application/vnd.mojian.selection-v1"
    fun translatedStroke(stroke: Stroke, offset: Float): Stroke {
        val inputs = MutableStrokeInputBatch()
        for (index in 0 until stroke.inputs.size) {
            val p = stroke.inputs.get(index)
            inputs.add(p.toolType, p.x + offset, p.y + offset, p.elapsedTimeMillis,
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
        }
        val shape = ShapeStrokeCodec.encode(stroke)
        val brush = if (shape == null) stroke.brush else ShapeStrokeCodec.brush(
            InkPalette.entries[shape.getInt("color")], stroke.brush.size / ShapeStrokeCodec.UNITS_PER_MM,
            shape.getInt("opacityPercent"), stroke.brush.colorIntArgb)
        return Stroke(brush, inputs)
    }

    fun translatedObject(item: InsertObject, offset: Float, assets: Map<String, String>): InsertObject {
        val json = item.toJson().apply {
            put("id", "paste-${UUID.randomUUID()}")
            put("x", item.documentX + offset); put("y", item.documentY + offset)
            put("pageBackground", false)
            listOf("assetName", "sourceAssetName").forEach { key ->
                assets[optString(key)]?.let { put(key, it) }
            }
        }
        return InsertObject.fromJson(json)
    }

    fun assetNames(item: InsertObject): List<String> = when (item) {
        is InsertObject.Image -> listOfNotNull(item.assetName, item.sourceAssetName)
        is InsertObject.Attachment -> listOf(item.assetName)
        is InsertObject.Spreadsheet -> listOf(item.assetName)
        else -> emptyList()
    }
}

/** SVG keeps the real brush boundary, including pressure width and closed shapes. */
object SelectionSvg {
    fun number(value: Float): String = String.format(Locale.ROOT, "%.5f", value).trimEnd('0').trimEnd('.')
    fun inkPaths(strokes: List<Stroke>): String = buildString {
        val position = MutableVec()
        strokes.forEach { stroke ->
            val color = stroke.brush.colorIntArgb
            val shape = stroke.shape
            for (group in 0 until shape.getRenderGroupCount()) {
                append("<path fill=\"").append(String.format(Locale.ROOT, "#%06X", color and 0xffffff))
                    .append("\" fill-opacity=\"").append(number(Color.alpha(color) / 255f)).append("\" fill-rule=\"nonzero\" d=\"")
                for (outline in 0 until shape.getOutlineCount(group)) {
                    for (vertex in 0 until shape.getOutlineVertexCount(group, outline)) {
                        shape.populateOutlinePosition(group, outline, vertex, position)
                        append(if (vertex == 0) "M" else "L").append(number(position.x)).append(' ').append(number(position.y))
                    }
                    append('Z')
                }
                append("\"/>")
            }
        }
    }
}
