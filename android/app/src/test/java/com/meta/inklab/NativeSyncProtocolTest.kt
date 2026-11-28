package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSyncProtocolTest {
    @Test fun aVersionedTombstoneNeedsAnAcknowledgedBaseBeforeRecreation() {
        val tombstone = NativeSyncEntry(null, 3)
        val local = "a".repeat(64)
        assertEquals(NativeSyncDecision.Conflict, NativeSyncDecisions.decideEntry(null, local, tombstone))
        assertEquals(NativeSyncDecision.Conflict, NativeSyncDecisions.decideEntry(NativeSyncEntry(null, 2), local, tombstone))
        assertEquals(NativeSyncDecision.Upload, NativeSyncDecisions.decideEntry(tombstone, local, tombstone))
    }

    @Test fun partialReceiptKeepsOperationIdentityAndOnlyAffectedPaths() {
        val source = JSONObject().put("workspaceId", "workspace").put("operationId", "0123456789abcdef")
            .put("revision", 8).put("files", JSONObject().put("library.json", JSONObject().put("hash", JSONObject.NULL).put("version", 8)))
        val receipt = NativeSyncManifest.parse(source)
        assertEquals("0123456789abcdef", receipt.operationId)
        assertEquals(setOf("library.json"), receipt.files.keys)
        assertNull(receipt.files.getValue("library.json").hash)
    }

    @Test fun unsafeAndNonIntegerVersionsAreRejectedWithoutTruncation() {
        listOf<Any>(9007199254740992L, 1.5, "12", -1).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { NativeSyncFiles.safeInteger(JSONObject().put("revision", value), "revision") }
        }
        assertEquals(9007199254740991L, NativeSyncFiles.safeInteger(JSONObject().put("revision", 9007199254740991L), "revision"))
    }

    @Test fun reservedNamesTrailingDotsAndCaseCollisionsAreRejected() {
        listOf("测试本/CON/page.mojian", "测试本/lpt0.mojian", ".mojian/assets/page/image.", "测试本/尾空格 /页.mojian").forEach {
            assertFalse(NativeSyncClient.validPath(it))
        }
        val hash = "a".repeat(64)
        fun parse(files: JSONObject) = NativeSyncManifest.parse(JSONObject().put("workspaceId", "workspace").put("revision", 1).put("files", files))
        assertThrows(IllegalArgumentException::class.java) {
            parse(JSONObject().put("测试本/page.mojian", NativeSyncEntry(hash, 1).json()).put("测试本/PAGE.mojian", NativeSyncEntry(hash, 1).json()))
        }
        // A case-only rename leaves the old spelling as a tombstone beside the new live entry.
        val renamed = parse(JSONObject().put("测试本/page.mojian", NativeSyncEntry(null, 1).json()).put("测试本/Page.mojian", NativeSyncEntry(hash, 1).json()))
        assertEquals(hash, renamed.files.getValue("测试本/Page.mojian").hash)
        val folder = parse(JSONObject().put("测试本", NativeSyncEntry(NativeSyncFiles.DIRECTORY, 1).json()))
        assertEquals(NativeSyncFiles.DIRECTORY, folder.files.getValue("测试本").hash)
        assertTrue(NativeSyncEntry(NativeSyncFiles.DIRECTORY, 1).json().getBoolean("directory"))
    }
}
