using Notein_win.Models;

namespace Notein_win.Services;

// Serialize frozen model copies on one worker. Coalesce only jobs that have not
// started; navigation, deletion, export and close flush before touching storage.
public sealed class PageSaveQueue
{
    private readonly object _gate = new();
    private sealed record Job(WorkspaceStore Store, PageDocument Page, NotebookLibrary? Library, long Sequence);
    private readonly Dictionary<string, Job> _pending = new();
    private readonly Dictionary<string, (Job Job, Exception Error)> _failed = new();
    private Task _worker = Task.CompletedTask;
    private bool _running;
    private long _sequence;
    public event Action<Exception?>? Completed;
    public bool IsIdle { get { lock (_gate) return !_running && _pending.Count == 0; } }
    public bool HasFailures { get { lock (_gate) return _failed.Count != 0; } }

    public void Enqueue(WorkspaceStore store, PageDocument snapshot, NotebookLibrary? library = null)
    {
        lock (_gate)
        {
            var key = store.RootDirectory + "/" + snapshot.NotebookId + "/" + snapshot.PageId;
            _pending[key] = new Job(store, snapshot, library, ++_sequence);
            if (!_running) { _running = true; _worker = Task.Run(WritePending); }
        }
    }

    private void WritePending()
    {
        while (true)
        {
            Job job;
            string key;
            lock (_gate)
            {
                if (_pending.Count == 0)
                {
                    _running = false;
                    Completed?.Invoke(_failed.Values.FirstOrDefault().Error);
                    return;
                }
                var first = _pending.MinBy(pair => pair.Value.Sequence);
                key = first.Key;
                job = first.Value;
                _pending.Remove(first.Key);
            }
            try
            {
                using var mutation = NativeSyncMutationEpoch.ForRoot(job.Store.RootDirectory).BeginMutation();
                lock (WorkspaceFileGate.ForRoot(job.Store.RootDirectory))
                {
                    job.Store.WritePageSnapshot(job.Page);
                    if (job.Library != null) job.Store.SaveLibrary(job.Library);
                }
                lock (_gate) _failed.Remove(key);
            }
            catch (Exception ex)
            {
                lock (_gate) _failed[key] = (job, ex);
                // Keep working: an error on one page must not discard other pages.
            }
        }
    }

    public void Flush()
    {
        Task worker;
        lock (_gate) worker = _worker;
        worker.GetAwaiter().GetResult();
        Job[] retries;
        lock (_gate) retries = _failed.Values.Select(value => value.Job).ToArray();
        foreach (var retry in retries) Enqueue(retry.Store, retry.Page, retry.Library);
        lock (_gate) worker = _worker;
        worker.GetAwaiter().GetResult();
        lock (_gate)
            if (_failed.Count != 0) throw new System.IO.IOException("笔记保存失败，请保留窗口并重试。", _failed.Values.First().Error);
    }
}
