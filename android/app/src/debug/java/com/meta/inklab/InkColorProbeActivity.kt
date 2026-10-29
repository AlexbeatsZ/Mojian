package com.meta.inklab

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Native pixel check using isolated views; never reads notes or preferences. */
class InkColorProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = JSONObject()
        try {
            val samples = JSONArray()
            for (scale in listOf(.5f, 1f, 3.2f)) {
                val view = DryInkView(this).apply {
                    backgroundFormat = CanvasBackgroundFormat.BLANK
                    layout(0, 0, 700, 400)
                    setViewport(Matrix().apply { setScale(scale, scale) })
                }
                val colors = listOf(InkPalette.BLACK.argb, InkPalette.BLUE.argb, InkPalette.RED.argb, Color.rgb(239,239,239))
                val strokes = colors.mapIndexed { index, color ->
                    val batch = MutableStrokeInputBatch()
                    repeat(61) { point -> batch.add(InputToolType.STYLUS, (50f+point*10f)/scale, (60f+index*85f)/scale, point*8L, pressure=.5f) }
                    Stroke(BrushProfiles.create(.4f*DOCUMENT_UNITS_PER_MM, colorArgb=color), batch)
                }
                view.replaceStrokes(strokes)
                val bitmap = Bitmap.createBitmap(700,400,Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(128,128,128))
                view.draw(canvas)
                colors.forEachIndexed { index, expected ->
                    var distance = Int.MAX_VALUE
                    var actual = 0
                    for (y in (60+index*85-2)..(60+index*85+2)) {
                        val pixel = bitmap.getPixel(300,y)
                        val delta = abs(Color.red(pixel)-Color.red(expected)) + abs(Color.green(pixel)-Color.green(expected)) + abs(Color.blue(pixel)-Color.blue(expected))
                        if(delta<distance) { actual=pixel;distance=delta }
                    }
                    samples.put(JSONObject().put("scale",scale).put("expected",String.format("#%08X",expected))
                        .put("actual",String.format("#%08X",actual)).put("rgbError",distance))
                }
                if(scale==1f) File(cacheDir,"ink-color-probe.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            result.put("samples",samples).put("status","measured")
            val legacy = JSONObject().put("id","color-probe-legacy").put("kind",0).put("color",2)
                .put("brushProfile","fountain-v1").put("opacityPercent",32).put("widthMm",.4)
                .put("points",JSONArray().apply { repeat(61) { i -> put(JSONObject().put("x",20+i*3)
                    .put("y",30).put("pressure",.5).put("elapsedMilliseconds",i*8)) } })
            val originalJson = legacy.toString()
            val restored = SharedStrokeCodec.decode(legacy, displayColor = { it.argb })
            check(restored.brush.colorIntArgb == InkPalette.RED.argb) { "Legacy pen was not opaque" }
            check(SharedStrokeCodec.encode(restored).toString() == originalJson) { "Read changed source metadata" }
            val view = DryInkView(this).apply { backgroundFormat=CanvasBackgroundFormat.BLANK;layout(0,0,700,400);replaceStrokes(listOf(restored)) }
            val bitmap=Bitmap.createBitmap(700,400,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);view.draw(canvas)
            val nativeY=(30/SharedWorkspaceCodec.TO_SHARED).toInt()
            val solid=(nativeY-2..nativeY+2).map { y-> bitmap.getPixel(300,y) }.minBy { pixel ->
                abs(Color.red(pixel)-Color.red(InkPalette.RED.argb))+abs(Color.green(pixel)-Color.green(InkPalette.RED.argb))+abs(Color.blue(pixel)-Color.blue(InkPalette.RED.argb)) }
            check(abs(Color.green(solid)-Color.green(InkPalette.RED.argb))<=2) { "Legacy pen pixel remained pale: ${String.format("#%08X",solid)}" }
            bitmap.recycle()
            for(opacity in listOf(0,3,32,85,100)) {
                for(kind in listOf(0,1)) {
                    val outline=JSONObject(legacy.toString()).put("kind",kind).put("vectorPath","M20,20L80,20L80,40L20,40Z")
                        .put("opacityPercent",opacity)
                    val stroke=SharedStrokeCodec.decode(outline, displayColor = { it.argb })
                    check(Color.alpha(stroke.brush.colorIntArgb)==opacity*255/100) { "Imported outline alpha changed" }
                    check(SharedStrokeCodec.encode(stroke).toString()==outline.toString())
                }
            }
            val crossing="M10,30 L90,30 90,50 10,50 Z M40,10 L60,10 60,90 40,90 Z"
            val intersectionCases=JSONArray()
            for(prefix in listOf("","F1 ","F0 ")) {
                val source=JSONObject(legacy.toString()).put("vectorPath",prefix+crossing).put("opacityPercent",50)
                val stroke=SharedStrokeCodec.decode(source,displayColor={it.argb})
                val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888)
                try {
                    check(SharedStrokeCodec.draw(Canvas(image),stroke))
                    val unit=SharedWorkspaceCodec.TO_SHARED.toFloat()
                    val armAlpha=Color.alpha(image.getPixel((20/unit).toInt(),(40/unit).toInt()))
                    val crossAlpha=Color.alpha(image.getPixel((50/unit).toInt(),(40/unit).toInt()))
                    check(armAlpha==127 && crossAlpha==if(prefix=="F0 ") 0 else armAlpha) { "Outline crossing fill/opacity changed" }
                    check(SharedStrokeCodec.encode(stroke).toString()==source.toString()) { "Display rewrote source outline" }
                    val svg=SelectionSvg.inkPaths(listOf(stroke))
                    check(svg.contains("fill-rule=\""+(if(prefix=="F0 ") "evenodd" else "nonzero")+"\""))
                    intersectionCases.put(JSONObject().put("prefix",prefix).put("armAlpha",armAlpha).put("crossAlpha",crossAlpha))
                } finally { image.recycle() }
            }
            result.put("legacyPenPixel",String.format("#%08X",solid)).put("sourceOpacityPreserved",true)
                .put("outlineOpacityCases",10).put("outlineIntersections",intersectionCases).put("status","passed")
        } catch (failure: Throwable) {
            result.put("status","failed").put("error",failure.toString())
        }
        File(cacheDir,"ink-color-probe.json").writeText(result.toString(),Charsets.UTF_8)
        finish()
    }
}
