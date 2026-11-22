package com.meta.inklab

import android.content.Context
import android.graphics.RenderNode
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DryInkTileLifecycleIntegrationTest {
    @Test fun discardedDisplayListsReappearOnTheNextFrameWithoutEditsOrZoom() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            verifyDiscardedDisplayLists(ApplicationProvider.getApplicationContext())
        }
    }

    @Test fun reopeningTheWindowRecordsInkImmediately() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            verifyWindowVisibility(ApplicationProvider.getApplicationContext())
        }
    }

    companion object {
        // Also callable in an isolated app_process with the product/test APKs on its classpath.
        // That runs the native regression on a device without installing a test over its notes app.
        @JvmStatic fun verifyDiscardedDisplayLists(context: Context) {
            val view = fixture(context)
            val revision = view.contentRevision
            var recorded = 0
            view.sceneRenderer = { _, strokes, draw -> recorded++; strokes.forEach(draw) }
            try {
                drawFrame(view)
                check(recorded == 2 && tiles(view).size == 2) { "Initial ink must record both visible tiles" }
                drawFrame(view)
                check(recorded == 2) { "Valid display lists must stay cached" }
                val lost = tiles(view).getValue(0L)
                val intact = tiles(view).getValue(1L shl 32)
                lost.discardDisplayList()
                drawFrame(view)
                check(lost.hasDisplayList() && intact.hasDisplayList()) { "Discarded tile remained blank" }
                check(recorded == 3) { "Only the discarded tile should be re-recorded" }
                check(tiles(view).getValue(0L) === lost) { "Reuse the node after its display list is discarded" }
                tiles(view).values.forEach(RenderNode::discardDisplayList)
                drawFrame(view)
                check(recorded == 5 && tiles(view).values.all(RenderNode::hasDisplayList)) { "Full HWUI eviction left blank tiles" }
                check(view.contentRevision == revision && view.strokeCount() == 1) { "Cache recovery must not edit ink" }
                view.replaceStrokes(view.snapshotStrokes())
                drawFrame(view)
                check(recorded == 7) { "Page binding must show existing strokes on its first frame" }
                println("PASS discarded display lists: initial, reuse, partial/full eviction, page binding")
            } finally { hideWindow(view) }
        }

        @JvmStatic fun verifyWindowVisibility(context: Context) {
            val view = fixture(context)
            var recorded = 0
            view.sceneRenderer = { _, strokes, draw -> recorded++; strokes.forEach(draw) }
            try {
                drawFrame(view)
                hideWindow(view)
                check(tiles(view).isEmpty()) { "Hidden window retained stale scene tiles" }
                windowVisibility(view, View.VISIBLE)
                drawFrame(view)
                check(recorded == 4 && tiles(view).values.all(RenderNode::hasDisplayList)) { "Reopened window must draw ink immediately" }
                println("PASS window visibility: hidden cache released, first visible frame recorded")
            } finally { hideWindow(view) }
        }

        private fun fixture(context: Context): DryInkView = DryInkView(context).apply {
            layout(0, 0, 1024, 512)
            val json = JSONObject().put("id", "tile-lifecycle-fixture").put("colorHex", "#FF0000")
                .put("widthMm", 1).put("vectorPath", "M100,100 L900,100 L900,120 L100,120 Z")
                .put("vectorBounds", JSONArray(listOf(100, 100, 900, 120)))
                .put("points", JSONArray())
            replaceStrokes(listOf(SharedStrokeCodec.decode(json, { android.graphics.Color.RED })))
        }

        private fun drawFrame(view: DryInkView) {
            val root = RenderNode("TileLifecycleTest")
            val canvas = root.beginRecording(view.width, view.height)
            try { view.draw(canvas) } finally { root.endRecording(); root.discardDisplayList() }
        }

        @Suppress("UNCHECKED_CAST")
        private fun tiles(view: DryInkView): Map<Long, RenderNode> =
            DryInkView::class.java.getDeclaredField("sceneTiles").apply { isAccessible = true }.get(view) as Map<Long, RenderNode>

        private fun windowVisibility(view: DryInkView, visibility: Int) {
            View::class.java.getDeclaredMethod("onWindowVisibilityChanged", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(view, visibility)
        }

        private fun hideWindow(view: DryInkView) = windowVisibility(view, View.GONE)
    }
}
