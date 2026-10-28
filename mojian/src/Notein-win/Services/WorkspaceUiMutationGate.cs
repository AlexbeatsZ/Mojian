namespace Notein_win.Services;

public sealed class WorkspaceUiMutationGate
{
    private readonly int _threadId = Environment.CurrentManagedThreadId;
    private bool _active;

    public bool TryRun(Func<bool> eligible, Action operation)
    {
        VerifyThread();
        if (_active || !eligible()) return false;
        _active = true;
        try { operation(); return true; }
        finally { _active = false; }
    }

    public bool Run(Func<bool> operation)
    {
        var result = false;
        if (!TryRun(() => true, () => result = operation()))
            throw new InvalidOperationException("笔记库变更正在进行，请稍后重试。");
        return result;
    }

    private void VerifyThread()
    {
        if (Environment.CurrentManagedThreadId != _threadId)
            throw new InvalidOperationException("笔记库变更必须在所属 UI 线程执行。");
    }
}
