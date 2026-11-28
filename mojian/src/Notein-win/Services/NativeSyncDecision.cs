namespace Notein_win.Services;

public enum NativeSyncDecision { Equal, Upload, Receive, Conflict, Failed }

public static class NativeSyncDecisions
{
    /// <summary>
    /// Three-way decision on identities (a file hash, <see cref="NativeSyncIdentity.Directory"/> or null). As in Syncthing a
    /// folder has no content to lose, so it wins over a deletion on the other side; a file on one side and a folder on the
    /// other is a failed item, never guessed.
    /// </summary>
    public static NativeSyncDecision DecideEntry(SyncEntry? baseline, string? local, SyncEntry? remote)
    {
        // A tombstone is a versioned event, even when both hashes are null.
        if (local != null && local != NativeSyncIdentity.Directory && baseline?.Hash == null && remote is { Hash: null }
            && baseline?.Version != remote.Version) return NativeSyncDecision.Conflict;
        var decision = Decide(baseline?.Hash, local, remote?.Hash);
        if (decision != NativeSyncDecision.Conflict) return decision;
        if (local == NativeSyncIdentity.Directory && remote?.Hash == null) return NativeSyncDecision.Upload;
        if (remote?.Hash == NativeSyncIdentity.Directory && local == null) return NativeSyncDecision.Receive;
        if (local == NativeSyncIdentity.Directory || remote?.Hash == NativeSyncIdentity.Directory) return NativeSyncDecision.Failed;
        return decision;
    }

    public static NativeSyncDecision Decide(string? baseline, string? local, string? remote)
    {
        if (local == remote) return NativeSyncDecision.Equal;
        if (remote == baseline) return NativeSyncDecision.Upload;
        if (local == baseline) return NativeSyncDecision.Receive;
        return NativeSyncDecision.Conflict;
    }
}
