package com.meta.inklab

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject

/** Shape JSON uses the desktop's 96-DPI document coordinates; tablet ink uses its native units. */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
object ShapeStrokeCodec {
    const val UNITS_PER_MM = 12.16225f
    private const val SCALE = (96f / 25.4f) / UNITS_PER_MM
    private const val PREFIX = "mojian-shape-v1:"

    fun create(points: List<ShapePoint>, color: InkPalette, widthMm: Float, opacity: Int, displayColor: Int, id: String = "shape-${java.util.UUID.randomUUID()}"): Stroke {
        return create(points, brush(color, widthMm, opacity, displayColor, id))
    }

    fun brush(color: InkPalette, widthMm: Float, opacity: Int, displayColor: Int, id: String = "shape-${java.util.UUID.randomUUID()}"): Brush {
        val family = BrushFamily(
            tip = BrushTip(cornerRounding = 1f),
            clientBrushFamilyId = "$PREFIX${color.name}:$widthMm:$opacity:$id",
            inputModel = BrushFamily.EXPERIMENTAL_NAIVE_MODEL,
        )
        return Brush.createWithColorIntArgb(family, Color.argb(opacity*255/100, Color.red(displayColor), Color.green(displayColor), Color.blue(displayColor)), widthMm*UNITS_PER_MM, .01f)
    }

    fun create(points: List<ShapePoint>, brush: Brush): Stroke {
        val inputs = MutableStrokeInputBatch()
        points.forEachIndexed { index, point ->
            inputs.add(InputToolType.STYLUS, point.x, point.y, index*8L, pressure = .5f)
        }
        return Stroke(brush, inputs)
    }

    fun encode(stroke: Stroke): JSONObject? {
        val id = stroke.brush.family.clientBrushFamilyId
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split(':')
        val inputs = stroke.inputs
        return JSONObject().apply {
            put("id", parts[3])
            put("kind", 0); put("brushProfile", "shape-v1")
            put("color", InkPalette.valueOf(parts[0]).ordinal)
            put("widthMm", (stroke.brush.size / UNITS_PER_MM).toDouble()); put("opacityPercent", parts[2].toInt())
            put("points", JSONArray().apply {
                for (i in 0 until inputs.size) {
                    val p = inputs.get(i)
                    put(JSONObject().put("x", (p.x*SCALE).toDouble()).put("y", (p.y*SCALE).toDouble())
                        .put("pressure", .5).put("elapsedMilliseconds", p.elapsedTimeMillis))
                }
            })
        }
    }

    fun decode(json: JSONObject, displayColor: (InkPalette) -> Int): Stroke? {
        if (json.optString("brushProfile") != "shape-v1") return null
        return runCatching {
            val color = if (json.opt("color") is Number) InkPalette.entries[json.optInt("color",0).coerceIn(0,2)] else InkPalette.valueOf(json.optString("color", "Black").uppercase())
            val array = json.getJSONArray("points")
            val points = (0 until array.length()).map { val p=array.getJSONObject(it); ShapePoint(p.getDouble("x").toFloat()/SCALE,p.getDouble("y").toFloat()/SCALE) }
            if (points.size < 2) return null
            create(points,color,json.optDouble("widthMm",.4).toFloat().coerceAtLeast(.01f),json.optInt("opacityPercent",100).coerceIn(1,100),displayColor(color),json.optString("id", "shape-${java.util.UUID.randomUUID()}"))
        }.getOrNull()
    }
}
