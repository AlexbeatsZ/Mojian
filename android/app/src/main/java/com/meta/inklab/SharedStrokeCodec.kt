package com.meta.inklab

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.PathParser
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/** Exact source outlines remain separate from AndroidX authoring meshes. */
object SharedStrokeCodec {
    private data class Record(val json: JSONObject, var path: Path? = null, val visible: Boolean = true)
    private val records = WeakHashMap<Stroke,Record>()
    private val unit = SharedWorkspaceCodec.TO_SHARED.toFloat()

    fun metadata(stroke: Stroke): JSONObject? = records[stroke]?.json
    fun visible(stroke: Stroke): Boolean = records[stroke]?.visible ?: true
    fun decode(json: JSONObject, displayColor: (InkPalette)->Int, visible: Boolean = true): Stroke {
        val palette = InkPalette.entries[json.optInt("color").coerceIn(0,2)]
        val argb = json.optString("colorHex").takeIf { it.isNotBlank() }?.let(Color::parseColor) ?: displayColor(palette)
        val width = json.optDouble("widthMm",.4).toFloat().coerceAtLeast(.01f) * DOCUMENT_UNITS_PER_MM
        val opacity = StoredInkAppearance.opacityPercent(json)
        val brush = when {
            // Outlines already own their brush geometry; native proxies must not clamp source alpha
            // to the interactive highlighter tool's 10-70% range.
            json.optString("vectorPath").isNotBlank() -> ShapeStrokeCodec.brush(palette,width / DOCUMENT_UNITS_PER_MM,opacity,argb,json.getString("id"))
            json.optString("brushProfile") == "shape-v1" -> ShapeStrokeCodec.brush(palette,width / DOCUMENT_UNITS_PER_MM,opacity,argb,json.getString("id"))
            json.optInt("kind") == 1 -> BrushProfiles.createHighlighter(width,argb,opacity)
            else -> BrushProfiles.create(width,json.optInt("pressurePercent",200)/100f,json.optInt("inkFlowPercent",100)/100f,
                Color.argb(opacity*255/100,Color.red(argb),Color.green(argb),Color.blue(argb)))
        }
        val batch = MutableStrokeInputBatch()
        val inputs = json.getJSONArray("points")
        // Imported outlines already contain pressure geometry. A two-point native proxy avoids
        // rebuilding hundreds of thousands of mesh samples just to display that existing geometry.
        if (json.optString("vectorPath").isNotBlank()) {
            val bounds = json.optJSONArray("vectorBounds")
            val first = if (inputs.length()>0) inputs.getJSONObject(0) else JSONObject().put("x",0).put("y",0)
            batch.add(InputToolType.STYLUS,(bounds?.optDouble(0) ?: first.getDouble("x")).toFloat()/unit,
                (bounds?.optDouble(1) ?: first.getDouble("y")).toFloat()/unit,0L,pressure=.5f)
            batch.add(InputToolType.STYLUS,(bounds?.optDouble(2) ?: first.getDouble("x")).toFloat()/unit,
                (bounds?.optDouble(3) ?: first.getDouble("y")).toFloat()/unit,8L,pressure=.5f)
        } else {
            require(inputs.length()>0) { "A stored stroke has no points or outline" }
            var elapsed = -1L
            SharedWorkspaceCodec.items(inputs).forEach { p ->
                elapsed = maxOf(elapsed+1,p.optLong("elapsedMilliseconds"))
                batch.add(InputToolType.STYLUS,p.getDouble("x").toFloat()/unit,p.getDouble("y").toFloat()/unit,elapsed,
                    pressure=p.optDouble("pressure",.5).toFloat().coerceIn(.01f,1f))
            }
        }
        return Stroke(brush,batch).also { records[it] = Record(json,visible=visible) }
    }

    fun encode(stroke: Stroke): JSONObject {
        records[stroke]?.let { return it.json }
        val shape = ShapeStrokeCodec.encode(stroke)
        val color = stroke.brush.colorIntArgb
        val palette = InkPalette.entries.firstOrNull { (it.argb and 0xffffff)==(color and 0xffffff) } ?: InkPalette.BLACK
        val logicalBlack = (color and 0xffffff) in listOf(0xEFEFEF,0x101317,0x1B1B1F,0x202020)
        val json = shape ?: JSONObject().put("id","stroke-${java.util.UUID.randomUUID()}")
            .put("kind",if (Color.alpha(color)<240) 1 else 0).put("brushProfile","fountain-v1")
            .put("color",palette.ordinal).put("colorHex",if(logicalBlack) "" else SharedWorkspaceCodec.hex(color))
            .put("widthMm",stroke.brush.size / DOCUMENT_UNITS_PER_MM)
            .put("opacityPercent",Color.alpha(color)*100/255)
            .put("points",JSONArray().apply { for(i in 0 until stroke.inputs.size) {
                val p = stroke.inputs.get(i)
                put(JSONObject().put("x",p.x*unit).put("y",p.y*unit).put("pressure",if(p.pressure.isFinite()) p.pressure else .5f)
                    .put("elapsedMilliseconds",p.elapsedTimeMillis))
            } })
        val familyId = stroke.brush.family.clientBrushFamilyId
        val pressure = Regex("-p([0-9.]+)-s([0-9.]+)").find(familyId)
        json.put("pressurePercent",((pressure?.groupValues?.get(1)?.toFloatOrNull() ?: 2f)*100).toInt())
            .put("inkFlowPercent",((pressure?.groupValues?.get(2)?.toFloatOrNull() ?: 1f)*100).toInt())
        // Store the actual filled AndroidX outline, so the desktop uses the same brush result.
        val outline = StringBuilder()
        val position = MutableVec()
        val geometry = stroke.shape
        val bounds = RectF(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY)
        for(group in 0 until geometry.getRenderGroupCount()) for(contour in 0 until geometry.getOutlineCount(group)) {
            for(vertex in 0 until geometry.getOutlineVertexCount(group,contour)) {
                geometry.populateOutlinePosition(group,contour,vertex,position)
                val x = position.x*unit; val y=position.y*unit
                bounds.left=minOf(bounds.left,x);bounds.top=minOf(bounds.top,y);bounds.right=maxOf(bounds.right,x);bounds.bottom=maxOf(bounds.bottom,y)
                outline.append(if(vertex==0) "M" else "L").append(x).append(',').append(y).append(' ')
            }
            outline.append("Z ")
        }
        if (outline.isNotEmpty() && bounds.left.isFinite()) json.put("vectorPath",outline.toString())
            .put("vectorBounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
            .put("vectorTransform",JSONArray(listOf(1,0,0,1,0,0)))
        records[stroke] = Record(json)
        return json
    }

    fun path(stroke: Stroke): Path? {
        val record = records[stroke] ?: return null
        val text = record.json.optString("vectorPath")
        if (text.isBlank()) return null
        record.path?.let { return it }
        val path = PathParser.createPathFromPathData(text.removePrefix("F0").removePrefix("F1").trim()) ?: error("Invalid stored outline")
        path.fillType = if(text.startsWith("F0")) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val t = record.json.optJSONArray("vectorTransform") ?: JSONArray(listOf(1,0,0,1,0,0))
        val matrix = Matrix().apply { setValues(floatArrayOf(t.getDouble(0).toFloat()/unit,t.getDouble(2).toFloat()/unit,t.getDouble(4).toFloat()/unit,
            t.getDouble(1).toFloat()/unit,t.getDouble(3).toFloat()/unit,t.getDouble(5).toFloat()/unit,0f,0f,1f)) }
        path.transform(matrix); record.path=path
        return path
    }
    fun draw(canvas: Canvas,stroke: Stroke,printing: Boolean = false): Boolean {
        if(!visible(stroke)) return true
        val path = path(stroke) ?: return false
        val color = stroke.brush.colorIntArgb
        canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=if(printing) OutputAppearance.printInk(color) else color;style=Paint.Style.FILL })
        return true
    }
    fun bounds(stroke: Stroke): StrokeViewportBounds? = path(stroke)?.let { p ->
        val rect=RectF();p.computeBounds(rect,true);StrokeViewportBounds(rect.left,rect.top,rect.right,rect.bottom)
    }
    fun hits(stroke: Stroke,x: Float,y: Float,radius: Float): Boolean? = if(!visible(stroke)) false else path(stroke)?.let { p ->
        val scale = 4f
        val local=Path(p);local.transform(Matrix().apply { setScale(scale,scale) })
        val clip=Region(((x-radius)*scale).toInt(),((y-radius)*scale).toInt(),((x+radius)*scale+1).toInt(),((y+radius)*scale+1).toInt())
        Region().apply { setPath(local,clip) }.isEmpty.not()
    }
    fun recolored(old: Stroke,next: Stroke) { records[old]?.let { records[next]=it } }
    fun transformed(old: Stroke,next: Stroke,transform: (Float,Float)->DocumentPoint,copy: Boolean=false) {
        val source=records[old]?.json ?: return
        val value=SharedWorkspaceCodec.copy(source)
        if(copy) { value.put("id","stroke-${java.util.UUID.randomUUID()}");value.put("layerId","");value.put("zOrder",0) }
        val origin=transform(0f,0f);val dx=transform(1f,0f);val dy=transform(0f,1f)
        val a=dx.x-origin.x;val b=dx.y-origin.y;val c=dy.x-origin.x;val d=dy.y-origin.y
        val t=value.optJSONArray("vectorTransform") ?: JSONArray(listOf(1,0,0,1,0,0))
        val m=(0..5).map { t.getDouble(it) }
        value.put("vectorTransform",JSONArray(listOf(a*m[0]+c*m[1],b*m[0]+d*m[1],a*m[2]+c*m[3],b*m[2]+d*m[3],
            a*m[4]+c*m[5]+origin.x*unit,b*m[4]+d*m[5]+origin.y*unit)))
        SharedWorkspaceCodec.items(value.optJSONArray("points")).forEach { p ->
            val mapped=transform(p.getDouble("x").toFloat()/unit,p.getDouble("y").toFloat()/unit)
            p.put("x",mapped.x*unit).put("y",mapped.y*unit)
        }
        value.put("widthMm",next.brush.size / DOCUMENT_UNITS_PER_MM)
        records[next]=Record(value,visible=visible(old))
    }
}
