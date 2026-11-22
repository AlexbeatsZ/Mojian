using System.Windows.Threading;

namespace Notein_win.Services;

/// <summary>
/// Undo-button taps: when enabled, a second tap within <see cref="DoubleTapInterval"/> redoes
/// instead of undoing, so a single tap's undo waits for that interval. Any other input flushes a
/// pending undo first, so it can never apply after a later edit. Keyboard/pen-key undo is unchanged.
/// </summary>
public sealed class UndoTapGesture
{
    public static readonly TimeSpan DoubleTapInterval = TimeSpan.FromMilliseconds(300);
    private readonly Action _undo;
    private readonly Action _redo;
    private readonly Func<bool> _enabled;
    private readonly DispatcherTimer _timer;

    public UndoTapGesture(Action undo, Action redo, Func<bool> enabled, Dispatcher? dispatcher = null)
    {
        _undo = undo;
        _redo = redo;
        _enabled = enabled;
        _timer = new DispatcherTimer(DispatcherPriority.Input, dispatcher ?? Dispatcher.CurrentDispatcher) { Interval = DoubleTapInterval };
        _timer.Tick += (_, _) => Flush();
    }

    public bool IsPending => _timer.IsEnabled;

    public void Tap()
    {
        if (!_enabled())
        {
            Flush();
            _undo();
            return;
        }
        if (IsPending)
        {
            _timer.Stop();
            _redo();
            return;
        }
        _timer.Start();
    }

    /// <summary>Applies a pending single-tap undo now.</summary>
    public void Flush()
    {
        if (!IsPending) return;
        _timer.Stop();
        _undo();
    }
}
