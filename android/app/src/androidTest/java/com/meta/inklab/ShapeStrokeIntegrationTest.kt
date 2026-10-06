package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeStrokeIntegrationTest {
    @Test fun nativeShapeRoundTripRenderMoveEraseAndUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context=ApplicationProvider.getApplicationContext<android.content.Context>()
            val view=DryInkView(context).apply { backgroundFormat=CanvasBackgroundFormat.BLANK; layout(0,0,700,600) }
            for (kind in ShapeKind.entries) {
                val original=ShapeStrokeCodec.create(ShapeGeometry.points(kind,ShapePoint(100f,100f),ShapePoint(500f,400f)),InkPalette.RED,.4f,100,InkPalette.RED.argb)
                val json=ShapeStrokeCodec.encode(original)!!
                val restored=ShapeStrokeCodec.decode(json){it.argb}!!
                assertEquals(json.getString("id"),ShapeStrokeCodec.encode(restored)!!.getString("id"))
                assertEquals(original.inputs.size,restored.inputs.size)
                for (i in 0 until original.inputs.size) {
                    assertEquals(original.inputs.get(i).x,restored.inputs.get(i).x,.0001f)
                    assertEquals(original.inputs.get(i).y,restored.inputs.get(i).y,.0001f)
                }
                view.replaceStrokes(listOf(restored))
                val bitmap=Bitmap.createBitmap(700,600,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels=IntArray(700*600); bitmap.getPixels(pixels,0,700,0,0,700,600)
                assertTrue("$kind rendered native pixels", pixels.any { Color.red(it)>120 && Color.alpha(it)>100 })
                bitmap.recycle()
                assertEquals(1,view.selectAll())
                view.translateSelection(20f,30f)
                val moved=view.snapshotStrokes().single()
                assertEquals(restored.inputs.get(0).x+20f,moved.inputs.get(0).x,.001f)
                assertNotNull(ShapeStrokeCodec.encode(moved))
                view.scaleSelection(0f,0f,2f)
                val resized=view.snapshotStrokes().single()
                val resizedRestored=ShapeStrokeCodec.decode(ShapeStrokeCodec.encode(resized)!!){it.argb}!!
                assertEquals(resized.brush.size,resizedRestored.brush.size,.001f)
                val point=resized.inputs.get(0)
                assertEquals(1,view.eraseAtDocument(point.x,point.y,12f))
                assertEquals(0,view.strokeCount())
            }
            val line=ShapeStrokeCodec.create(listOf(ShapePoint(100f,100f),ShapePoint(500f,300f)),InkPalette.BLACK,.4f,100,InkPalette.BLACK.argb)
            view.addStrokes(listOf(line)); assertTrue(view.undo()); assertEquals(0,view.strokeCount()); assertTrue(view.redo()); assertEquals(1,view.strokeCount())
        }
    }
}
