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
    @Test fun workerPlanContainsGeometryAndCancellationStopsFurtherPreparation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"export-test-${System.nanoTime()}").apply { mkdirs() }
        val session=ExportProductSession(context,PageContentRepository(root),"notebook",
            listOf(ExportPageIdentity("page","Blank",PageViewSettings(),null)))
        val task=FutureTask {
            assertEquals(1,session.plan(ExportOptions()).pages.size)
            val lease=session.acquire(0)
            try { assertEquals(0,lease.source.content.size) } finally { lease.close() }
            session.cancel()
            try { session.plan(ExportOptions()); fail("Cancelled session accepted work") }
            catch (_: CancellationException) {}
        }
        try { Thread(task).start(); task.get() }
        finally { session.cancel(); root.deleteRecursively() }
    }
}
