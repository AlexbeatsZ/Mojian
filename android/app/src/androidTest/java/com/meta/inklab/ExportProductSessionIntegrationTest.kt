package com.meta.inklab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask

@RunWith(AndroidJUnit4::class)
class ExportProductSessionIntegrationTest {
    @Test fun globalThemeControlsEveryOffscreenPageWithoutChangingStoredPageAppearance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "theme-export-${System.nanoTime()}").apply { mkdirs() }
        val repository = PageContentRepository(root)
        val stored = listOf(PageViewSettings(darkBackground=false, backgroundFormat=CanvasBackgroundFormat.BLANK, pageTitleVisible=false),
            PageViewSettings(darkBackground=true, pageColor=PageColor.MINT, backgroundFormat=CanvasBackgroundFormat.BLANK, pageTitleVisible=false))
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            for (dark in listOf(false, true)) {
                val preferences = AppearancePreferences(darkBackground=dark)
                val session = ExportProductSession(context, repository, "notebook", stored.mapIndexed { index, settings ->
                    ExportPageIdentity("page-$index", "", preferences.display(settings), null)
                })
                try {
                    assertEquals(dark, session.images().first)
                    stored.indices.forEach { index ->
                        session.acquire(index).use { lease ->
                            assertEquals(dark, lease.source.settings.darkBackground)
                            lease.source.render(android.graphics.Canvas(bitmap), android.graphics.Matrix(), ExportOptions())
                            assertEquals(stored[index].pageColor.displayArgb(dark), bitmap.getPixel(20, 20))
                        }
                    }
                } finally { session.cancel() }
            }
            org.junit.Assert.assertFalse(stored[0].darkBackground)
            org.junit.Assert.assertTrue(stored[1].darkBackground)
        } finally { bitmap.recycle(); root.deleteRecursively() }
    }

    @Test fun sourceDelegatesSoftwareCanvasAndCannotDrawAfterLeaseCloseOrCancel() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"export-render-test-${System.nanoTime()}").apply { mkdirs() }
        val repository=PageContentRepository(root)
        repository.save(PageInsertDocument("notebook", "page", sharedSource=org.json.JSONObject().put(
            "textItems", org.json.JSONArray().put(org.json.JSONObject().put("id","text")
                .put("text","Export text").put("x",24).put("y",48).put("width",300).put("height",100)))))
        val session=ExportProductSession(context,repository,"notebook",
            listOf(ExportPageIdentity("page","Text",PageViewSettings(),null)))
        val bitmap=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val lease=session.acquire(0)
            val source=lease.source
            org.junit.Assert.assertTrue(source.content.isNotEmpty())
            source.render(android.graphics.Canvas(bitmap),android.graphics.Matrix(),ExportOptions())
            lease.close()
            try {
                source.render(android.graphics.Canvas(bitmap),android.graphics.Matrix(),ExportOptions())
                fail("Closed source rendered")
            } catch (_: IllegalStateException) {}
            val next=session.acquire(0)
            val cancelledSource=next.source
            session.cancel()
            next.close()
            try {
                cancelledSource.render(android.graphics.Canvas(bitmap),android.graphics.Matrix(),ExportOptions())
                fail("Cancelled source rendered")
            } catch (_: CancellationException) {}
        } finally { session.cancel(); bitmap.recycle(); root.deleteRecursively() }
    }

    @Test fun workerPlanContainsGeometryAndCancellationStopsFurtherPreparation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"export-test-${System.nanoTime()}").apply { mkdirs() }
        val session=ExportProductSession(context,PageContentRepository(root),"notebook",
            listOf(ExportPageIdentity("page","Blank",PageViewSettings(),null)))
        val task=FutureTask {
            assertEquals(1,session.plan(ExportOptions()).pages.size)
            val lease=session.acquire(0)
            try {
                assertEquals(0,lease.source.content.size)
                try { session.acquire(0); fail("Concurrent lease accepted") }
                catch (_: IllegalStateException) {}
            } finally { lease.close() }
            session.acquire(0).use { secondLease ->
                lease.close()
                assertEquals(0,secondLease.source.content.size)
                try {
                    session.acquire(0).use { fail("Closing old lease released the active lease") }
                } catch (_: IllegalStateException) {}
            }
            try { session.acquire(1); fail("Invalid page accepted") }
            catch (_: IndexOutOfBoundsException) {}
            session.acquire(0).close()
            session.cancel()
            try { session.plan(ExportOptions()); fail("Cancelled session accepted work") }
            catch (_: CancellationException) {}
        }
        try { Thread(task).start(); task.get() }
        finally { session.cancel(); root.deleteRecursively() }
    }
}
