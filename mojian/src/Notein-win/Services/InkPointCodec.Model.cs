using Notein_win.Models;

namespace Notein_win.Services;

public static partial class InkPointCodec
{
    public static List<InkPoint> Decode(string text) => DecodeRows(text).Select(row => new InkPoint
    {
        X = (float)row[0]!.Value, Y = (float)row[1]!.Value,
        Pressure = row[2] is { } pressure ? (float)pressure : .5f,
        ElapsedMilliseconds = row[3] is { } elapsed ? (long)elapsed : 0,
        TiltX = row[4] is { } tiltX ? (float)tiltX : 0, TiltY = row[5] is { } tiltY ? (float)tiltY : 0,
        TiltRadians = row[6] is { } tilt ? (float)tilt : null,
        OrientationRadians = row[7] is { } orientation ? (float)orientation : null,
    }).ToList();

    /// <summary>Null when the points carry per-point extension data or mixed optional channels.</summary>
    public static string? TryEncode(IReadOnlyList<InkPoint> points)
    {
        if (points.Count == 0 || points.Any(p => p.AdditionalData is { Count: > 0 } || !float.IsFinite(p.X) || !float.IsFinite(p.Y))) return null;
        var tilt = points[0].TiltRadians.HasValue;
        var orientation = points[0].OrientationRadians.HasValue;
        if (points.Any(p => p.TiltRadians.HasValue != tilt || p.OrientationRadians.HasValue != orientation)) return null;
        // Windows always stores pressure, time and degree tilt, as its JSON objects did.
        var mask = 0b111111 | (tilt ? 1 << 6 : 0) | (orientation ? 1 << 7 : 0);
        return Encode(points.Count, mask, (i, channel) => channel switch
        {
            0 => points[i].X, 1 => points[i].Y, 2 => points[i].Pressure, 3 => points[i].ElapsedMilliseconds,
            4 => points[i].TiltX, 5 => points[i].TiltY, 6 => points[i].TiltRadians!.Value, _ => points[i].OrientationRadians!.Value,
        });
    }
}
