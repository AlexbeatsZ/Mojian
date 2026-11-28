package com.meta.inklab

enum class NativeSyncDecision { Equal, Upload, Receive, Conflict, Failed }

object NativeSyncDecisions {
    /**
     * Three-way decision on identities (a file hash, [NativeSyncFiles.DIRECTORY] or null). As in Syncthing a folder has no
     * content to lose, so it wins over a deletion on the other side; a file on one side and a folder on the other is a
     * failed item, never guessed. Shared in meaning with the Windows client (sync-decisions fixture).
     */
    fun decideEntry(baseline: NativeSyncEntry?, local: String?, remote: NativeSyncEntry?): NativeSyncDecision {
        // A tombstone is a versioned event, even when both hashes are null.
        if (local != null && local != NativeSyncFiles.DIRECTORY && baseline?.hash == null && remote != null && remote.hash == null &&
            baseline?.version != remote.version) return NativeSyncDecision.Conflict
        val decision = decide(baseline?.hash, local, remote?.hash)
        if (decision != NativeSyncDecision.Conflict) return decision
        return when {
            local == NativeSyncFiles.DIRECTORY && remote?.hash == null -> NativeSyncDecision.Upload
            remote?.hash == NativeSyncFiles.DIRECTORY && local == null -> NativeSyncDecision.Receive
            local == NativeSyncFiles.DIRECTORY || remote?.hash == NativeSyncFiles.DIRECTORY -> NativeSyncDecision.Failed
            else -> decision
        }
    }

    fun decide(baseline: String?, local: String?, remote: String?): NativeSyncDecision = when {
        local == remote -> NativeSyncDecision.Equal
        remote == baseline -> NativeSyncDecision.Upload
        local == baseline -> NativeSyncDecision.Receive
        else -> NativeSyncDecision.Conflict
    }
}
