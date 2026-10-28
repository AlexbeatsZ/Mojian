using System.Collections.Concurrent;
using System.IO;

namespace Notein_win.Services;

public sealed class NativeSyncMutationEpoch
{
    private static readonly ConcurrentDictionary<string, NativeSyncMutationEpoch> Workspaces = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();
    private long _epoch;
    private int _active;

    private NativeSyncMutationEpoch() { }

    public static NativeSyncMutationEpoch ForRoot(string root) => Workspaces.GetOrAdd(
        Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)), _ => new NativeSyncMutationEpoch());

    public IDisposable BeginMutation()
    {
        lock (_gate)
        {
            _epoch++;
            _active++;
            return new Mutation(this);
        }
    }

    public long? Capture()
    {
        lock (_gate) return _active == 0 ? _epoch : null;
    }

    public bool PublishIfUnchanged(long expected, Action publish)
    {
        lock (_gate)
        {
            if (_active != 0 || _epoch != expected) return false;
            publish();
            return true;
        }
    }

    private sealed class Mutation(NativeSyncMutationEpoch owner) : IDisposable
    {
        private NativeSyncMutationEpoch? _owner = owner;

        public void Dispose()
        {
            var owner = Interlocked.Exchange(ref _owner, null);
            if (owner == null) return;
            lock (owner._gate)
            {
                owner._active--;
                owner._epoch++;
            }
        }
    }
}
