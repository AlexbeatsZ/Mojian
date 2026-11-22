package com.meta.inklab

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictReviewTest {
    private val review = JSONArray(requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/json-review.json")).bufferedReader().use { it.readText() })

    @Test
    fun pathsMapToPagesAndAssets() {
        assertEquals("n1" to "p1", SyncConflictReview.pageOf("notebooks/n1/pages/p1.json"))
        assertEquals("n1" to "p1", SyncConflictReview.pageOf("notebooks/n1/assets/p1/image.png"))
        assertNull(SyncConflictReview.pageOf("library.json"))
        assertNull(SyncConflictReview.pageOf("notebooks/n1/notebook.json"))
    }

    @Test
    fun entriesLabelLibraryPagesAndAssetsWithLibraryFirst() {
        val items = listOf(
            NativeSyncConflictItem("notebooks/n/assets/p/scan.png", "add", null, File("r"), null),
            NativeSyncConflictItem("notebooks/n/pages/p.json", "modify", File("l"), File("r"), NativeSyncMerge.Summary(1, 1, 1)),
            NativeSyncConflictItem("notebooks/n/pages/gone.json", "delete", File("l"), null, null),
            NativeSyncConflictItem("library.json", "modify", File("l"), File("r"), null),
        )
        val entries = SyncConflictReview.entries(items) { _, page -> if (page == "p") "作业 4" else null }
        assertEquals(listOf("笔记目录", "gone", "scan.png", "作业 4"), entries.map { it.label })
        val page = entries.single { it.label == "作业 4" }
        assertTrue(page.isPage && page.canKeepBoth)
        assertEquals(ConflictMark.MODIFY, page.mark)
        assertEquals(ConflictMark.DELETE, entries.single { it.label == "gone" }.mark)
        val asset = entries.single { it.label == "scan.png" }
        assertEquals(ConflictMark.ADD, asset.mark)
        assertFalse(asset.isPage || asset.canKeepBoth)
        assertEquals("p", asset.pageId)
    }

    @Test
    fun summaryTextOmitsZeroCounts() {
        assertEquals("本机 +1 · 服务器 +1 · 改动 1", SyncConflictReview.summaryText(NativeSyncMerge.Summary(1, 1, 1)))
        assertEquals("服务器 +3", SyncConflictReview.summaryText(NativeSyncMerge.Summary(0, 3, 0)))
        assertEquals("", SyncConflictReview.summaryText(null))
    }

    @Test
    fun strokeDiffSeparatesServerOnlyLocalOnlyAndChanged() {
        val case = review.getJSONObject(0)
        val diff = SyncConflictReview.strokeDiff(case.getJSONObject("local"), case.getJSONObject("remote"))
        assertEquals(listOf("r"), diff.remote.map { it.getString("id") })
        assertEquals(listOf("l"), diff.local.map { it.getString("id") })
        // "a" changed on both sides: this device's version is marked.
        assertEquals(listOf("a" to 2), diff.changed.map { it.getString("id") to it.optInt("color") })
    }

    @Test
    fun strokeDiffTreatsNumericSpellingsAsEqualAndMissingSidesAsAllNew() {
        val local = JSONObject("""{"strokes":[{"id":"a","widthMm":1}]}""")
        val remote = JSONObject("""{"strokes":[{"id":"a","widthMm":1.0}]}""")
        val same = SyncConflictReview.strokeDiff(local, remote)
        assertTrue(same.remote.isEmpty() && same.local.isEmpty() && same.changed.isEmpty())
        val added = SyncConflictReview.strokeDiff(null, remote)
        assertEquals(1, added.remote.size)
        assertTrue(added.local.isEmpty())
    }

    @Test
    fun libraryChangesListPagesAndSectionsOfEachSide() {
        val case = review.getJSONObject(1)
        val changes = SyncConflictReview.libraryChanges(case.getJSONObject("local"), case.getJSONObject("remote"))
        assertEquals(
            listOf(
                LibraryChange(LibraryChange.Side.CHANGED, "n / s / mine"),
                LibraryChange(LibraryChange.Side.LOCAL, "n / s / q"),
                LibraryChange(LibraryChange.Side.REMOTE, "n / t"),
            ),
            changes,
        )
    }

    @Test
    fun libraryChangesSeeAPageRemovedFromAnOtherwiseEmptySection() {
        val local = JSONObject("""{"notebooks":[{"id":"n","title":"数学","sections":[{"id":"s","title":"作业","pages":[]}]}]}""")
        val remote = JSONObject("""{"notebooks":[{"id":"n","title":"数学","sections":[{"id":"s","title":"作业","pages":[{"id":"p","title":"第 5 题"}]}]}]}""")
        assertEquals(listOf(LibraryChange(LibraryChange.Side.REMOTE, "数学 / 作业 / 第 5 题")), SyncConflictReview.libraryChanges(local, remote))
    }
}
