using System.Text.Json;

namespace Notein_win.Models;

public static class FountainV2Contract
{
    private static readonly JsonElement Source = Load();
    public static readonly double FullPressureWidth = Source.GetProperty("fullPressureWidth").GetDouble();
    public static readonly int PressureSegments = Source.GetProperty("pressureSegments").GetInt32();
    public static readonly double ThinningCap = Source.GetProperty("speedThinningCap").GetDouble();
    public static readonly double MinimumSpeedMultiplier = Source.GetProperty("minimumSpeedMultiplier").GetDouble();
    public static readonly double DirectionMinimum = Source.GetProperty("directionMinimum").GetDouble();
    public static readonly double DirectionRange = Source.GetProperty("directionMaximum").GetDouble() - DirectionMinimum;
    public static readonly double DirectionAmount = Source.GetProperty("directionAmount").GetDouble();
    public static readonly double ResponseMilliseconds = Source.GetProperty("responseMilliseconds").GetDouble();
    public static readonly double StabilityMiddle = Source.GetProperty("stabilityMiddleMilliseconds").GetDouble();
    public static readonly double StabilityMaximum = Source.GetProperty("stabilityMaximumMilliseconds").GetDouble();
    public static readonly int UpsamplingHz = Source.GetProperty("upsamplingHz").GetInt32();
    public static readonly (double Speed, double Thinning)[] SpeedCurve = Source.GetProperty("speedCurve").EnumerateArray()
        .Select(point => (point[0].GetDouble(), point[1].GetDouble())).ToArray();

    private static JsonElement Load()
    {
        using var stream = typeof(FountainV2Contract).Assembly.GetManifestResourceStream("Mojian.FountainV2.json")
            ?? throw new InvalidOperationException("缺少共享笔迹参数。");
        using var json = JsonDocument.Parse(stream);
        return json.RootElement.Clone();
    }
}
