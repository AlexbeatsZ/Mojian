package com.meta.inklab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExportRenderWorkerIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun viewsTextMeasurementDrawingAndCleanupHaveOneNonMainOwner() {
        val worker = ExportRenderWorker(context)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        var owner: Thread? = null
        var layer: CanvasTextInputLayer? = null
        val cleaned = CountDownLatch(1)
        try {
            worker.call {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                assertSame(Looper.myLooper(), worker.context().mainLooper)
                owner = Thread.currentThread()
                layer = CanvasTextInputLayer(worker.context(), ViewportState()).apply {
                    replaceEditors(listOf(CanvasTextSnapshot("Worker rich text", 12f, 24f, 300, 180, 1f, 0f, 1f)))
                    val spec = View.MeasureSpec.makeMeasureSpec(512, View.MeasureSpec.EXACTLY)
                    measure(spec, spec)
                    layout(0, 0, 512, 512)
                }
                worker.setCleanup {
                    assertSame(owner, Thread.currentThread())
                    layer!!.replaceEditors(emptyList())
                    layer = null
                    cleaned.countDown()
                }
            }
            worker.call {
                assertSame(owner, Thread.currentThread())
                assertTrue(layer!!.contentBounds().any())
                layer!!.renderForExport(Canvas(bitmap), Matrix())
                worker.call { assertSame(owner, Thread.currentThread()) }
            }
            assertTrue((0 until bitmap.height).any { row ->
                (0 until bitmap.width).any { column -> bitmap.getPixel(column, row) != 0 }
            })
        } finally {
            worker.close()
            assertTrue(cleaned.await(10, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(10_000))
            bitmap.recycle()
        }
    }

    @Test fun cancelDuringWorkDoesNotBlockMainAndInterruptedWaitStillOwnsCleanup() {
        val worker = ExportRenderWorker(context)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val result = FutureTask {
            worker.call {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                worker.setCleanup { cleaned.countDown() }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        }
        val caller = Thread(result)
        try {
            caller.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            caller.interrupt()
            val mainClosed = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { worker.close(); mainClosed.countDown() }
            assertTrue(mainClosed.await(5, TimeUnit.SECONDS))
            try { worker.call { fail("Closed worker accepted work") }; fail("No cancellation") }
            catch (_: CancellationException) {}
        } finally {
            proceed.countDown()
            worker.close()
            caller.join(10_000)
            assertFalse(caller.isAlive)
            result.get(1, TimeUnit.SECONDS)
            assertTrue(cleaned.await(10, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(10_000))
        }
    }
}
