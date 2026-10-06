namespace Notein_win.Services;

/// Accumulated real pen travel in document millimeters, latched until lift/cancel.
/// Candidate pressure/dwell resets must never reset this guard.
public sealed class PressureEraseTravelGuard
{
    private double _lastX, _lastY, _limit;
    private bool _active;
    public double DistanceMm { get; private set; }
    public bool Exceeded { get; private set; }

    public void Begin(double xMm, double yMm, double limitMm)
    {
        Reset();
        _active = true;
        _lastX = xMm; _lastY = yMm; _limit = limitMm;
    }

    public void Observe(double xMm, double yMm)
    {
        if (!_active || Exceeded) return;
        DistanceMm += Math.Sqrt(Math.Pow(xMm - _lastX, 2) + Math.Pow(yMm - _lastY, 2));
        _lastX = xMm; _lastY = yMm;
        if (DistanceMm > _limit) Exceeded = true;
    }

    public void Reset() { _active = false; DistanceMm = 0; Exceeded = false; }
}
