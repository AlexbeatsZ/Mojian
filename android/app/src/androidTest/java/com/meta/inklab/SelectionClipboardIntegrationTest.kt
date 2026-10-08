package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionClipboardIntegrationTest {
    @Test fun nativeStrokeCopyPreservesInputsAndShapeAndExportsActualSvgOutlines() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val source = ShapeStrokeCodec.create(listOf(ShapePoint(30f, 40f), ShapePoint(150f, 100f)),
                InkPalette.BLACK, .7f, 85, InkPalette.BLACK.argb)
            val copy = SelectionClipboard.translatedStroke(source, 18f)
            assertNotSame(source, copy)
            assertEquals(source.brush.size, copy.brush.size, .001f)
            assertEquals(source.brush.colorIntArgb, copy.brush.colorIntArgb)
            assertNotEquals(ShapeStrokeCodec.encode(source)!!.getString("id"), ShapeStrokeCodec.encode(copy)!!.getString("id"))
            assertEquals(source.inputs.size, copy.inputs.size)
            for (index in 0 until source.inputs.size) {
                val before = source.inputs.get(index); val after = copy.inputs.get(index)
                assertEquals(before.x + 18, after.x, .001f); assertEquals(before.y + 18, after.y, .001f)
                assertEquals(before.pressure, after.pressure, .001f)
                assertEquals(before.elapsedTimeMillis, after.elapsedTimeMillis)
                assertEquals(before.tiltRadians, after.tiltRadians, .001f)
                assertEquals(before.orientationRadians, after.orientationRadians, .001f)
            }
            assertNotNull(ShapeStrokeCodec.encode(copy))
            val view = DryInkView(context)
            view.replaceStrokes(listOf(source)); view.appendSelectedStrokes(listOf(copy))
            assertEquals(listOf(copy), view.snapshotSelectedStrokes())
            val paths = SelectionSvg.inkPaths(view.snapshotSelectedStrokes())
            assertTrue(paths.contains("<path")); assertTrue(paths.contains("fill=\"#1B1B1F\"")); assertFalse(paths.contains("<image"))
            view.setDarkBackground(true)
            assertTrue(SelectionSvg.inkPaths(view.snapshotSelectedStrokes()).contains("fill=\"#EFEFEF\""))
            val bitmap = Bitmap.createBitmap(220, 180, Bitmap.Config.ARGB_8888)
            view.renderVectorInkForExport(Canvas(bitmap), Matrix(), selectedOnly = true)
            assertEquals(0, bitmap.getPixel(0, 0))
            assertEquals(1, view.deleteSelection()); assertEquals(listOf(source), view.snapshotStrokes())
            bitmap.recycle()
        }
    }
}
