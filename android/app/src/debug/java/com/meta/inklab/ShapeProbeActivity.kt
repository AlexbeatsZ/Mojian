package com.meta.inklab

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Debug-only native probe. No MainActivity, preferences, page repository, or user note access. */
class ShapeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = JSONObject()
        try {
            val view = DryInkView(this).apply {
                backgroundFormat = CanvasBackgroundFormat.BLANK
                layout(0, 0, 900, 650)
            }
            val gallery = mutableListOf<androidx.ink.strokes.Stroke>()
            for ((index, kind) in ShapeKind.entries.withIndex()) {
                val original = ShapeStrokeCodec.create(
                    ShapeGeometry.points(kind, ShapePoint(100f,100f), ShapePoint(500f,400f)),
                    InkPalette.RED, .4f, 100, InkPalette.RED.argb,
                )
                val json = ShapeStrokeCodec.encode(original)!!
                val restored = ShapeStrokeCodec.decode(json) { it.argb }!!
                check(json.getString("id") == ShapeStrokeCodec.encode(restored)!!.getString("id"))
                check(original.inputs.size == restored.inputs.size)
                for (i in 0 until original.inputs.size) {
                    check(abs(original.inputs.get(i).x - restored.inputs.get(i).x) < .001f)
                    check(abs(original.inputs.get(i).y - restored.inputs.get(i).y) < .001f)
                }
                view.replaceStrokes(listOf(restored))
                val bitmap = Bitmap.createBitmap(900,650,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(900*650)
                bitmap.getPixels(pixels,0,900,0,0,900,650)
                check(pixels.any { Color.red(it)>120 && Color.alpha(it)>100 }) { "$kind did not render" }
                bitmap.recycle()
                check(view.selectAll()==1)
                view.translateSelection(20f,30f)
                val moved = view.snapshotStrokes().single()
                check(abs(moved.inputs.get(0).x - restored.inputs.get(0).x - 20f)<.001f)
                view.scaleSelection(0f,0f,2f)
                val resized = view.snapshotStrokes().single()
                val resizedRestored = ShapeStrokeCodec.decode(ShapeStrokeCodec.encode(resized)!!) { it.argb }!!
                check(abs(resized.brush.size - resizedRestored.brush.size)<.001f)
                val point = resized.inputs.get(0)
                check(view.eraseAtDocument(point.x,point.y,12f)==1)
                check(view.strokeCount()==0)
                val x = 30f + (index%3)*290f
                val y = 30f + (index/3)*210f
                gallery += ShapeStrokeCodec.create(
                    ShapeGeometry.points(kind,ShapePoint(x,y),ShapePoint(x+230f,y+140f)),
                    InkPalette.BLUE,.8f,100,InkPalette.BLUE.argb,
                )
            }
            view.addStrokes(listOf(gallery.first()))
            check(view.undo()); check(view.strokeCount()==0)
            check(view.redo()); check(view.strokeCount()==1)
            view.replaceStrokes(gallery)
            val preview = Bitmap.createBitmap(900,650,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(preview); canvas.drawColor(Color.WHITE); view.draw(canvas)
            File(cacheDir,"shape-probe.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG,100,it) }
            preview.recycle()
            result.put("status","passed").put("shapeKinds",ShapeKind.entries.size)
                .put("checks","native render, point/ID/width round trip, move, resize, whole-stroke erase, undo/redo")
            Log.i("MojianShapeProbe",result.toString())
        } catch (failure: Throwable) {
            result.put("status","failed").put("error",failure.toString())
            Log.e("MojianShapeProbe",result.toString(),failure)
        }
        File(cacheDir,"shape-probe.json").writeText(result.toString(),Charsets.UTF_8)
        finish()
    }
}
