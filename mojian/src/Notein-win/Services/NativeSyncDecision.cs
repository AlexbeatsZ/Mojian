namespace Notein_win.Services;

public enum NativeSyncDecision { Equal, Upload, Receive, Conflict }

public static class NativeSyncDecisions
{
    public static NativeSyncDecision DecideEntry(SyncEntry? baseline, string? local, SyncEntry? remote)
    {
        // A tombstone is a versioned event, even when both hashes are null.
        if (local != null && baseline?.Hash == null && remote is { Hash: null }
            && baseline?.Version != remote.Version) return NativeSyncDecision.Conflict;
        return Decide(baseline?.Hash, local, remote?.Hash);
    }

    public static NativeSyncDecision Decide(string? baseline, string? local, string? remote)
    {
        if (local == remote) return NativeSyncDecision.Equal;
        if (remote == baseline) return NativeSyncDecision.Upload;
        if (local == baseline) return NativeSyncDecision.Receive;
        return NativeSyncDecision.Conflict;
    }
}
