package com.meta.inklab

enum class NativeSyncDecision { Equal, Upload, Receive, Conflict }

object NativeSyncDecisions {
    fun decide(baseline: String?, local: String?, remote: String?): NativeSyncDecision = when {
        local == remote -> NativeSyncDecision.Equal
        remote == baseline -> NativeSyncDecision.Upload
        local == baseline -> NativeSyncDecision.Receive
        else -> NativeSyncDecision.Conflict
    }
}
