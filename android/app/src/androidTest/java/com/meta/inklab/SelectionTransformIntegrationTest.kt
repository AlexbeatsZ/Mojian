package com.meta.inklab

import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionTransformIntegrationTest {
    @Test fun nativeOverlayMovesTinyImageAndResizesCornersAndEdgesWithoutRatioClamp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val root = File(context.cacheDir, "selection-test-${System.nanoTime()}")
            try {
                val viewport = ViewportState().apply { setScaleAround(.5f, 0f, 0f) }
                val repository = PageContentRepository(root)
                val image = InsertObject.Image("tiny", 100f, 100f, 8f, 4f, "test.png", "test", "image/png", ImageSourceKind.IMAGE)
                val document = PageInsertDocument("test", "page", mutableListOf(image))
                val layer = InsertContentLayer(context, viewport, repository).apply { bind(document); selectAll() }
                val overlay = SelectionOverlayView(context, viewport).apply {
                    selectionBoundsProvider = layer::selectedBounds
                    selectedContentHitTest = { x, y ->
                        val p = viewport.viewToDocument(x, y)
                        RectF(image.documentX, image.documentY, image.documentX + image.width, image.documentY + image.height).contains(p.x, p.y)
                    }
                    onTranslateSelection = layer::translateSelection
                    onScaleSelection = { x, y, fx, fy -> layer.scaleSelection(x, y, fx, fy) }
                    onTransformFinished = layer::finishSelectionTransform
                }
                var time = android.os.SystemClock.uptimeMillis()
                fun pointer(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(time, time + 10, action, x, y, 0)
                    assertTrue(overlay.onTouchEvent(event))
                    event.recycle(); time += 10
                }
                pointer(MotionEvent.ACTION_DOWN, 52f, 51f)
                pointer(MotionEvent.ACTION_MOVE, 132f, 111f)
                pointer(MotionEvent.ACTION_UP, 132f, 111f)
                assertEquals(260f, image.documentX, .001f); assertEquals(220f, image.documentY, .001f)
                assertEquals(8f, image.width, .001f); assertEquals(4f, image.height, .001f)

                viewport.setScaleAround(1f, 0f, 0f)
                image.width = 80f; image.height = 40f
                val padding = 8f * context.resources.displayMetrics.density
                val downX = image.documentX + image.width + padding + 2f
                val downY = image.documentY + image.height + padding + 2f
                pointer(MotionEvent.ACTION_DOWN, downX, downY)
                pointer(MotionEvent.ACTION_MOVE, downX + 80f, downY + 20f)
                assertEquals(2f, image.width / image.height, .0001f)
                assertEquals(260f, image.documentX, .001f); assertEquals(220f, image.documentY, .001f)
                assertEquals(152f, image.width, .001f); assertEquals(76f, image.height, .001f)
                pointer(MotionEvent.ACTION_MOVE, downX, downY)
                pointer(MotionEvent.ACTION_UP, downX, downY)
                assertEquals(80f, image.width, .001f); assertEquals(40f, image.height, .001f)

                val edgeX = image.documentX + image.width + padding
                val edgeY = image.documentY + image.height / 2
                pointer(MotionEvent.ACTION_DOWN, edgeX, edgeY)
                pointer(MotionEvent.ACTION_MOVE, edgeX + 80f, edgeY + 100f)
                pointer(MotionEvent.ACTION_UP, edgeX + 80f, edgeY + 100f)
                assertEquals(160f, image.width, .001f); assertEquals(40f, image.height, .001f)
                val restored = repository.load("test", "page").objects.single()
                assertEquals(image.width, restored.width, .001f); assertEquals(image.height, restored.height, .001f)
                image.width = 8f; image.height = 4f
                layer.scaleSelection(0f, 0f, .5f)
                assertEquals(4f, image.width, .001f); assertEquals(2f, image.height, .001f)
            } finally { root.deleteRecursively() }
        }
    }
}
