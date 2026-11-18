using System.Text.Json;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

/// <summary>
/// Shared packed stroke samples (`pointsCoded`), see shared/ink-points.md. Per-channel zigzag
/// LEB128 deltas of quantized integers, like AndroidX Ink's CodedNumericRun and ISF packet streams.
/// ~9 bytes/point instead of ~93 for a JSON object per point.
/// </summary>
public static class InkPointCodec
{
    public const string Prefix = "1:";
    // Channel order, JSON names and quanta are part of the shared contract.
    internal static readonly (string Name, double Scale)[] Channels =
    [
        ("x", 1000), ("y", 1000), ("pressure", 10000), ("elapsedMilliseconds", 1),
        ("tiltX", 100), ("tiltY", 100), ("tiltRadians", 10000), ("orientationRadians", 10000),
    ];

    private static long Quantize(double value, double scale)
    {
        var scaled = value * scale;
        return (long)(scaled >= 0 ? Math.Floor(scaled + .5) : -Math.Floor(-scaled + .5));
    }

    /// <summary>Encodes rows of channel values; null where a channel is absent (same for every row).</summary>
    internal static string Encode(int count, int mask, Func<int, int, double> value)
    {
        var bytes = new List<byte>(16 + count * 10);
        void Unsigned(ulong v) { while (v >= 0x80) { bytes.Add((byte)(v | 0x80)); v >>= 7; } bytes.Add((byte)v); }
        Unsigned((ulong)count);
        Unsigned((ulong)(mask >> 2));
        for (var channel = 0; channel < Channels.Length; channel++)
        {
            if ((mask & (1 << channel)) == 0) continue;
            long previous = 0;
            for (var i = 0; i < count; i++)
            {
                var q = Quantize(value(i, channel), Channels[channel].Scale);
                var delta = q - previous; previous = q;
                Unsigned((ulong)((delta << 1) ^ (delta >> 63)));
            }
        }
        return Prefix + Convert.ToBase64String(bytes.ToArray());
    }

    /// <summary>Decoded rows; absent channels are null.</summary>
    internal static double?[][] DecodeRows(string text)
    {
        if (!text.StartsWith(Prefix, StringComparison.Ordinal)) throw new JsonException("不支持的笔迹采样编码。");
        var bytes = Convert.FromBase64String(text[Prefix.Length..]);
        var offset = 0;
        ulong Unsigned()
        {
            ulong result = 0; var shift = 0;
            while (true)
            {
                if (offset >= bytes.Length || shift > 63) throw new JsonException("笔迹采样编码已损坏。");
                var b = bytes[offset++];
                result |= (ulong)(b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
        }
        var count = checked((int)Unsigned());
        var mask = (int)(Unsigned() << 2) | 3;
        var rows = new double?[count][];
        for (var i = 0; i < count; i++) rows[i] = new double?[Channels.Length];
        for (var channel = 0; channel < Channels.Length; channel++)
        {
            if ((mask & (1 << channel)) == 0) continue;
            long value = 0;
            for (var i = 0; i < count; i++)
            {
                var z = Unsigned();
                value += (long)(z >> 1) ^ -(long)(z & 1);
                rows[i][channel] = value / Channels[channel].Scale;
            }
        }
        if (offset != bytes.Length) throw new JsonException("笔迹采样编码已损坏。");
        return rows;
    }

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

    // Parsed nodes are JsonElement-backed; constructed nodes may hold any CLR numeric type.
    private static bool TryNumber(JsonValue value, out double number)
    {
        if (value.TryGetValue(out number)) return true;
        if (value.TryGetValue(out long l)) { number = l; return true; }
        if (value.TryGetValue(out int i)) { number = i; return true; }
        if (value.TryGetValue(out float f)) { number = f; return true; }
        if (value.TryGetValue(out decimal m)) { number = (double)m; return true; }
        return false;
    }

    /// <summary>Packs a JSON points array generically (any client's writer); null if not eligible.</summary>
    public static string? TryEncode(JsonArray points)
    {
        if (points.Count == 0) return null;
        var rows = new double[points.Count][];
        var mask = -1;
        for (var i = 0; i < points.Count; i++)
        {
            if (points[i] is not JsonObject point) return null;
            var row = new double[Channels.Length];
            var present = 0;
            foreach (var (key, node) in point)
            {
                var channel = Array.FindIndex(Channels, c => c.Name == key);
                if (channel < 0 || node is not JsonValue value || value.GetValueKind() != JsonValueKind.Number || !TryNumber(value, out var number)) return null;
                if (!double.IsFinite(number) || channel == 3 && number != Math.Floor(number)) return null;
                row[channel] = number; present |= 1 << channel;
            }
            if ((present & 3) != 3 || mask >= 0 && present != mask) return null;
            mask = present; rows[i] = row;
        }
        return Encode(rows.Length, mask, (i, channel) => rows[i][channel]);
    }
}
