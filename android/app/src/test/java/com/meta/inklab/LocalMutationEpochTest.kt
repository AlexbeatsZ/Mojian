package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalMutationEpochTest {
    @Test fun nestedWritesBlockSnapshotsAndInvalidateEarlierGeneration() {
        val epoch = LocalMutationEpoch.forWorkspace(File(System.getProperty("java.io.tmpdir"), ".agents/epoch-${java.util.UUID.randomUUID()}"))
        val before = requireNotNull(epoch.capture())
        val outer = epoch.begin()
        val inner = epoch.begin()
        assertNull(epoch.capture())
        inner.close()
        inner.close()
        assertNull(epoch.capture())
        outer.close()
        assertNotNull(epoch.capture())
        assertFalse(epoch.publishIfUnchanged(before) { fail("Stale snapshot must not publish") })
        assertTrue(epoch.publishIfUnchanged(requireNotNull(epoch.capture())) { })
    }

    @Test fun pathsInSameWorkspaceShareEpoch() {
        val root = File(System.getProperty("java.io.tmpdir"), ".agents/epoch-${java.util.UUID.randomUUID()}")
        assertSame(LocalMutationEpoch.forWorkspace(root), LocalMutationEpoch.forWorkspace(File(root, ".")))
    }
}
