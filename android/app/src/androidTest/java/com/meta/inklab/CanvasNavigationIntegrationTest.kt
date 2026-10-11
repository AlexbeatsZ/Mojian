package com.meta.inklab

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasNavigationIntegrationTest {
    @Test fun capturedNavigationStopsSecondaryAndHoverBeforeCanvasChildren() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context=ApplicationProvider.getApplicationContext<Context>()
            val host=CanvasInputHost(context)
            val guard=CanvasNavigationGuard()
            var secondaryEvents=0
            var childHover=0
            host.addView(object: View(context) {
                override fun onHoverEvent(event: MotionEvent): Boolean { childHover++;return true }
            },FrameLayout.LayoutParams(500,500))
            host.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY))
            host.layout(0,0,500,500)
            host.onPointerEventObserved={ event ->
                val fingers=(0 until event.pointerCount).count { event.getToolType(it)==MotionEvent.TOOL_TYPE_FINGER }
                val ends=fingers>0 && event.actionMasked==MotionEvent.ACTION_UP
                if(guard.observe(fingers,ends)!=NavigationCapture.NONE) true else {
                    if(event.actionMasked==MotionEvent.ACTION_BUTTON_RELEASE) secondaryEvents++
                    false
                }
            }
            fun send(action: Int,types: List<Int>,source: Int) {
                val props=types.mapIndexed { index,type -> MotionEvent.PointerProperties().apply { id=index;toolType=type } }.toTypedArray()
                val coords=types.mapIndexed { index,_ -> MotionEvent.PointerCoords().apply { x=100f+index*80;y=100f;pressure=1f;size=1f } }.toTypedArray()
                val event=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,types.size,props,coords,0,0,1f,1f,0,0,source,0)
                if(action==MotionEvent.ACTION_HOVER_MOVE) {
                    CanvasInputHost::class.java.getDeclaredMethod("dispatchHoverEvent",MotionEvent::class.java)
                        .apply { isAccessible=true }.invoke(host,event)
                }
                else if(action==MotionEvent.ACTION_BUTTON_RELEASE) host.dispatchGenericMotionEvent(event)
                else host.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_FINGER),InputDevice.SOURCE_TOUCHSCREEN)
            send(MotionEvent.ACTION_BUTTON_RELEASE,listOf(MotionEvent.TOOL_TYPE_MOUSE),InputDevice.SOURCE_MOUSE)
            send(MotionEvent.ACTION_HOVER_MOVE,listOf(MotionEvent.TOOL_TYPE_STYLUS),InputDevice.SOURCE_STYLUS)
            assertTrue(guard.active)
            assertEquals(0,secondaryEvents)
            assertEquals(0,childHover)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_FINGER),InputDevice.SOURCE_TOUCHSCREEN)
            send(MotionEvent.ACTION_MOVE,listOf(MotionEvent.TOOL_TYPE_FINGER),InputDevice.SOURCE_TOUCHSCREEN)
            assertTrue(guard.active)
            send(MotionEvent.ACTION_UP,listOf(MotionEvent.TOOL_TYPE_FINGER),InputDevice.SOURCE_TOUCHSCREEN)
            assertFalse(guard.active)
            send(MotionEvent.ACTION_BUTTON_RELEASE,listOf(MotionEvent.TOOL_TYPE_MOUSE),InputDevice.SOURCE_MOUSE)
            assertEquals(1,secondaryEvents)
        }
    }
}
