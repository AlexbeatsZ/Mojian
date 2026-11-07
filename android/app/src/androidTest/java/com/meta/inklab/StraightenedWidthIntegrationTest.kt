package com.meta.inklab

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StraightenedWidthIntegrationTest {
    @Test fun actualHoldTimerUsesShapeWidthAndKeepsSourceInkColorAndOpacity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // No onCreate / workspace binding: exercise the real timer and preview on isolated views.
            val activity = MainActivity()
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            val shelf = field("toolShelf").get(activity) as ToolShelf
            shelf.tools.first { it.id == "shape-default" }.apply { widthMm = 1.3f; straightenDelayMillis = 200L }
            val layer = DryInkView(ApplicationProvider.getApplicationContext())
            field("dryInkView").set(activity, layer)
            val tick = field("straightenTick").get(activity) as Runnable
            for (kind in listOf(DrawingToolKind.PEN, DrawingToolKind.HIGHLIGHTER)) {
                field("shapePreset").set(activity, ToolPreset("source", kind, color = InkPalette.BLUE, widthMm = .2f, opacityPercent = 32))
                field("shapeBrush").set(activity, null)
                field("shapeStart").set(activity, ShapePoint(10f, 20f))
                field("shapeEnd").set(activity, ShapePoint(150f, 40f))
                field("pressurePointerId").set(activity, 0)
                val hold = field("straighten").get(activity) as HoldToStraighten
                val now = SystemClock.uptimeMillis()
                hold.begin(ShapePoint(10f, 20f), now - 1000)
                hold.move(ShapePoint(150f, 40f), now - 900, 2.5f)
                tick.run()
                val stroke = layer.shapePreview
                assertNotNull(stroke)
                val json = ShapeStrokeCodec.encode(stroke!!)!!
                assertEquals(1.3, json.getDouble("widthMm"), .0001)
                assertEquals(InkPalette.BLUE.ordinal, json.getInt("color"))
                assertEquals(if (kind == DrawingToolKind.HIGHLIGHTER) 32 else 100, json.getInt("opacityPercent"))
                assertEquals("shape-v1", json.getString("brushProfile"))
                hold.cancel()
            }
        }
    }
}
