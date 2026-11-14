namespace Notein_win.Services;

public enum NativeSyncDecision { Equal, Upload, Receive, Conflict }

public static class NativeSyncDecisions
{
    public static NativeSyncDecision Decide(string? baseline, string? local, string? remote)
    {
        if (local == remote) return NativeSyncDecision.Equal;
        if (remote == baseline) return NativeSyncDecision.Upload;
        if (local == baseline) return NativeSyncDecision.Receive;
        return NativeSyncDecision.Conflict;
    }
}
