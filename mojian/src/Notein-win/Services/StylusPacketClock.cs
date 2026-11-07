namespace Notein_win.Services;

// WPF timestamps a whole packet, not each StylusPoint. Use event time (not dispatch time)
// and distribute its samples across the interval, including signed TickCount wraparound.
public sealed class StylusPacketClock
{
    private int _previousTimestamp;
    private long _eventElapsed;
    private long _lastSample;

    public void Reset(int timestamp)
    {
        _previousTimestamp = timestamp;
        _eventElapsed = _lastSample = 0;
    }

    public long[] Distribute(int timestamp, int count)
    {
        if (count <= 0) return [];
        var delta = unchecked((uint)(timestamp - _previousTimestamp));
        if (delta <= int.MaxValue) _eventElapsed += delta;
        _previousTimestamp = timestamp;
        var start = _lastSample;
        var end = Math.Max(_eventElapsed, start + count);
        var times = new long[count];
        for (var i = 0; i < count; i++)
            times[i] = start + (end - start) * (i + 1) / count;
        _lastSample = end;
        return times;
    }
}
