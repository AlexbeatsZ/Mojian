package com.meta.inklab

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasTextInputLayerTest {
    @Test
    fun existingTextRemainsSelectableAfterInsertionIsDisarmed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var host: CanvasInputHost
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            host = CanvasInputHost(context)
            layer = CanvasTextInputLayer(context, ViewportState())
            host.addView(layer, FrameLayout.LayoutParams(1200, 800))
            host.onPointerEventObserved = layer::observeCanvasPointerEvent
            layoutHost(host)
            layer.setTextInsertionArmed(true)
            dispatchTap(host, 240f, 240f, SystemClock.uptimeMillis())
            assertEquals(1, layer.childCount)
            layer.setTextInsertionArmed(false)
        }

        SystemClock.sleep(400)

        instrumentation.runOnMainSync {
            val start = SystemClock.uptimeMillis()
            dispatchTap(host, 240f, 240f, start)
            dispatchTap(host, 240f, 240f, start + 100)
            assertEquals(1, layer.selectedCount())
        }
    }

    @Test
    fun disarmedInsertionDoesNotCreateTextOnBlankTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            layer.layout(0, 0, 1200, 800)
            layer.setTextInsertionArmed(false)
            dispatchTap(layer, 700f, 500f, SystemClock.uptimeMillis())
            assertEquals(0, layer.childCount)
        }
    }

    @Test
    fun existingTextIsSelectableThroughTheRealCanvasParent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var host: CanvasInputHost
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            host = CanvasInputHost(context)
            layer = CanvasTextInputLayer(context, ViewportState())
            host.addView(
                View(context).apply {
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) layer.clearSelection()
                        true
                    }
                },
                FrameLayout.LayoutParams(1200, 800),
            )
            host.addView(layer, FrameLayout.LayoutParams(1200, 800))
            host.onPointerEventObserved = layer::observeCanvasPointerEvent
            layoutHost(host)
            layer.setTextInsertionArmed(true)
            dispatchTap(host, 240f, 240f, SystemClock.uptimeMillis())
            assertEquals(1, layer.childCount)
            layer.setTextInsertionArmed(false)
        }

        SystemClock.sleep(400)

        instrumentation.runOnMainSync {
            val start = SystemClock.uptimeMillis()
            dispatchTap(host, 240f, 240f, start)
            dispatchTap(host, 240f, 240f, start + 100)
            assertEquals(1, layer.selectedCount())
        }
    }

    @Test
    fun selectedTextEntersEditingOnTheNextFingerTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var host: CanvasInputHost
        lateinit var layer: CanvasTextInputLayer
        var editing = false
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            host = CanvasInputHost(context)
            host.addView(
                View(context).apply { setOnTouchListener { _, _ -> true } },
                FrameLayout.LayoutParams(1200, 800),
            )
            layer = CanvasTextInputLayer(context, ViewportState()).apply {
                onEditingChanged = { editing = it }
            }
            host.addView(layer, FrameLayout.LayoutParams(1200, 800))
            host.onPointerEventObserved = layer::observeCanvasPointerEvent
            layoutHost(host)
            layer.setTextInsertionArmed(true)
            dispatchTap(host, 240f, 240f, SystemClock.uptimeMillis())
            layer.setTextInsertionArmed(false)
        }

        SystemClock.sleep(400)

        instrumentation.runOnMainSync {
            val start = SystemClock.uptimeMillis()
            dispatchTap(host, 240f, 240f, start)
            dispatchTap(host, 240f, 240f, start + 100)
            assertEquals(1, layer.selectedCount())
            assertEquals(false, editing)
        }

        SystemClock.sleep(400)
        instrumentation.runOnMainSync {
            dispatchTap(host, 240f, 240f, SystemClock.uptimeMillis())
        }
        SystemClock.sleep(400)
        instrumentation.runOnMainSync {
            assertEquals(true, editing)
        }
    }

    private fun layoutHost(host: CanvasInputHost) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 1200, 800)
    }

    private fun dispatchTap(target: View, x: Float, y: Float, downTime: Long) {
        target.dispatchTouchEvent(fingerEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime))
        target.dispatchTouchEvent(fingerEvent(MotionEvent.ACTION_UP, x, y, downTime, downTime + 20))
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
