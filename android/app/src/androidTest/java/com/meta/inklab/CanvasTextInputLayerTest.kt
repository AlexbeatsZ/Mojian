package com.meta.inklab

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasTextInputLayerTest {
    @Test
    fun doubleTapSelectsExistingTextBoxWhileTextEditingIsEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            layer.layout(0, 0, 1200, 800)
            layer.setTextMode(true)
            dispatchTap(layer, 240f, 240f, SystemClock.uptimeMillis())
            assertEquals(1, layer.childCount)
        }

        SystemClock.sleep(400)

        instrumentation.runOnMainSync {
            val start = SystemClock.uptimeMillis()
            dispatchTap(layer, 240f, 240f, start)
            dispatchTap(layer, 240f, 240f, start + 100)
            assertEquals(1, layer.selectedCount())
        }
    }

    private fun dispatchTap(layer: CanvasTextInputLayer, x: Float, y: Float, downTime: Long) {
        layer.dispatchTouchEvent(fingerEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime))
        layer.dispatchTouchEvent(fingerEvent(MotionEvent.ACTION_UP, x, y, downTime, downTime + 20))
    }

    private fun fingerEvent(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): MotionEvent {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
