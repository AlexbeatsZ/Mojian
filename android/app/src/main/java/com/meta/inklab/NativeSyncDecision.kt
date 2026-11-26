package com.meta.inklab

enum class NativeSyncDecision { Equal, Upload, Receive, Conflict }

object NativeSyncDecisions {
    fun decideEntry(baseline: NativeSyncEntry?, local: String?, remote: NativeSyncEntry?): NativeSyncDecision {
        // A tombstone is a versioned event, even when both hashes are null.
        if (local != null && baseline?.hash == null && remote != null && remote.hash == null && baseline?.version != remote.version)
            return NativeSyncDecision.Conflict
        return decide(baseline?.hash, local, remote?.hash)
    }

    fun decide(baseline: String?, local: String?, remote: String?): NativeSyncDecision = when {
        local == remote -> NativeSyncDecision.Equal
        remote == baseline -> NativeSyncDecision.Upload
        local == baseline -> NativeSyncDecision.Receive
        else -> NativeSyncDecision.Conflict
    }
}
