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

    private fun pageFile(directory: File, id: String) = File(directory, "$id.mojian").apply {
        writeText(PageFileFormat.compose(id, "a0", JSONObject().put("title", id), JSONObject().put("strokes", JSONArray())).toString())
    }

    @Test
    fun pagesAreIdentifiedByHeadAndAssetsByFolder() {
        val directory = createTempDirectory()
        val head = pageFile(directory, "p1")
        assertEquals("p1", SyncConflictReview.pageIdOf(NativeSyncConflictItem("数学/作业/第 4 题.mojian", "modify", head, null, null)))
        assertEquals("p1", SyncConflictReview.pageIdOf(NativeSyncConflictItem(".mojian/assets/p1/image.png", "add", null, File("r"), null)))
        assertNull(SyncConflictReview.pageIdOf(NativeSyncConflictItem("数学/.mojian-folder.json", "modify", File("l"), File("r"), null)))
        assertNull(SyncConflictReview.pageIdOf(NativeSyncConflictItem("数学/坏页.mojian", "modify", File(directory, "missing"), null, null)))
    }

    @Test
    fun entriesLabelPagesByFileNameWithPagesFirst() {
        val directory = createTempDirectory()
        val items = listOf(
            NativeSyncConflictItem(".mojian/assets/p/scan.png", "add", null, File("r"), null),
            NativeSyncConflictItem("数学/作业 4.mojian", "modify", pageFile(directory, "p"), File("r"), NativeSyncMerge.Summary(1, 1, 1)),
            NativeSyncConflictItem("数学/gone.mojian", "delete", pageFile(directory, "gone"), null, null),
            NativeSyncConflictItem("数学/.mojian-folder.json", "modify", File("l"), File("r"), null),
        )
        val entries = SyncConflictReview.entries(items) { page -> if (page == "p") "n" else null }
        assertEquals(listOf("gone", "作业 4", ".mojian-folder.json", "scan.png"), entries.map { it.label })
        val page = entries.single { it.label == "作业 4" }
        assertTrue(page.isPage && page.canKeepBoth)
        assertEquals("n", page.notebookId)
        assertEquals(ConflictMark.MODIFY, page.mark)
        assertEquals(ConflictMark.DELETE, entries.single { it.label == "gone" }.mark)
        val asset = entries.single { it.label == "scan.png" }
        assertEquals(ConflictMark.ADD, asset.mark)
        assertFalse(asset.isPage || asset.canKeepBoth)
        assertEquals("p", asset.pageId)
        assertTrue(entries.single { it.label == ".mojian-folder.json" }.canKeepBoth)
    }

    private fun createTempDirectory() = kotlin.io.path.createTempDirectory("conflict-review").toFile().apply { deleteOnExit() }

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
}
