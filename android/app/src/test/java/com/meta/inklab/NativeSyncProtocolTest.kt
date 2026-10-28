package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSyncProtocolTest {
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
        listOf("notebooks/CON/pages/page.json", "notebooks/book/pages/lpt0.json", "notebooks/book/assets/page/image.").forEach {
            assertFalse(NativeSyncClient.validPath(it))
        }
        val files = JSONObject().put("notebooks/book/pages/page.json", NativeSyncEntry(null, 1).json())
            .put("notebooks/book/pages/PAGE.json", NativeSyncEntry(null, 1).json())
        assertThrows(IllegalArgumentException::class.java) {
            NativeSyncManifest.parse(JSONObject().put("workspaceId", "workspace").put("revision", 1).put("files", files))
        }
    }
}
