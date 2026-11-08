using System.Text.Json;
using System.Text.Json.Nodes;
using System.IO;

namespace Notein_win.Models;

public sealed record CurvePoint(double X, double Y);

// Color and nominal width belong to the tool. This is the complete, versioned mark behavior.
public sealed record PenStyle
{
    public const string Profile = "pen-style-v1";
    public int Version { get; init; } = 1;
    public bool PressureEnabled { get; set; } = true;
    public List<CurvePoint> PressureCurve { get; set; } = [];
    public bool SpeedEnabled { get; set; } = true;
    public List<CurvePoint> SpeedCurve { get; set; } = [];
    public bool DirectionEnabled { get; set; } = true;
    public double DirectionAmount { get; set; } = .17;
    public bool StartEnabled { get; set; }
    public double StartLengthMm { get; set; } = .5;
    public double StartMinimum { get; set; } = .55;
    public bool EndEnabled { get; set; }
    public double EndLengthMm { get; set; } = 1;
    public double EndMinimum { get; set; } = .18;
    public int StabilityPercent { get; set; } = 50;
    public bool TiltEnabled { get; set; }
    public double TiltWidth { get; set; } = 1.5;
    public bool TurnEnabled { get; set; }
    public double TurnAmount { get; set; } = .25;
    public int PressureResponseMs { get; set; }
    public int SpeedResponseMs { get; set; } = 25;
    public int DirectionResponseMs { get; set; } = 25;
    public double SpeedMaximumCmPerSecond { get; set; } = 64;
    public double DirectionMinimum { get; set; } = .45;
    public double DirectionMaximum { get; set; } = .65;
    public double TurnMinimum { get; set; } = 80;
    public double TurnMaximum { get; set; } = 230;
    public int TurnResponseMs { get; set; } = 25;
    public double TiltMaximumRadians { get; set; } = Math.PI / 3;
    public int InputWindowMs { get; set; }
    public int UpsamplingHz { get; set; } = 180;
    public int StartResponseMs { get; set; }
    public int EndResponseMs { get; set; }

    public PenStyle Copy() => this with { PressureCurve = [.. PressureCurve], SpeedCurve = [.. SpeedCurve] };

    public static PenStyle FromLegacy(int pressure = 200, int flow = 100, int stability = 50)
    {
        var gamma = Math.Clamp(pressure / 100d, 0, 4);
        var end = Math.Max(.05, 1 - .85 * Math.Clamp(flow / 100d, 0, 2));
        return new()
        {
            PressureCurve = Enumerable.Range(0, 17).Select(i => new CurvePoint(i / 16d, gamma == 0 ? 1.4 : 1.4 * Math.Pow(i / 16d, gamma))).ToList(),
            SpeedCurve = new (double V, double G)[] { (0, 0), (1, 0), (2, .08), (4, .15), (8, .23), (16, .46), (32, .69), (64, .85) }
                .Select(p => new CurvePoint(p.V, 1 - (1 - end) * p.G / .85)).ToList(),
            StabilityPercent = Math.Clamp(stability, 0, 100),
        };
    }

    public static double Evaluate(IReadOnlyList<CurvePoint> points, double x)
    {
        if (points.Count == 0) return 1;
        if (x <= points[0].X) return points[0].Y;
        for (var i = 1; i < points.Count; i++)
        {
            if (x > points[i].X) continue;
            var a = points[i - 1]; var b = points[i];
            return a.Y + (b.Y - a.Y) * Math.Clamp((x - a.X) / Math.Max(1e-8, b.X - a.X), 0, 1);
        }
        return points[^1].Y;
    }

    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
    public JsonObject ToJson() => JsonSerializer.SerializeToNode(this, JsonOptions)!.AsObject();
    public string Key() => (this with
    {
        PressureCurve = PressureCurve.Select(p => new CurvePoint(Math.Round(p.X, 10), Math.Round(p.Y, 10))).ToList(),
        SpeedCurve = SpeedCurve.Select(p => new CurvePoint(Math.Round(p.X, 10), Math.Round(p.Y, 10))).ToList(),
    }).ToJson().ToJsonString();
    public static PenStyle? FromJson(JsonObject? json)
    {
        try
        {
            var style = json?.Deserialize<PenStyle>(JsonOptions);
            return style is { Version: 1 } && ValidCurve(style.PressureCurve, 1) && ValidCurve(style.SpeedCurve, style.SpeedMaximumCmPerSecond) &&
                FiniteRange(style.SpeedMaximumCmPerSecond, 1, 1000) && FiniteRange(style.DirectionMinimum, -1, 1) && FiniteRange(style.DirectionMaximum, -1, 1) && style.DirectionMinimum < style.DirectionMaximum &&
                FiniteRange(style.TurnMinimum, 0, 100000) && FiniteRange(style.TurnMaximum, 0, 100000) && style.TurnMinimum < style.TurnMaximum &&
                FiniteRange(style.TiltMaximumRadians, .01, Math.PI / 2) && style.InputWindowMs is >= 0 and <= 500 && style.UpsamplingHz is >= 30 and <= 1000 &&
                style.TurnResponseMs is >= 0 and <= 100 && style.StartResponseMs is >= 0 and <= 100 && style.EndResponseMs is >= 0 and <= 100 &&
                style.StabilityPercent is >= 0 and <= 100 && style.PressureResponseMs is >= 0 and <= 100 &&
                style.SpeedResponseMs is >= 0 and <= 100 && style.DirectionResponseMs is >= 0 and <= 100 &&
                FiniteRange(style.DirectionAmount, 0, 1) && FiniteRange(style.TurnAmount, 0, 1) &&
                FiniteRange(style.StartLengthMm, .1, 20) && FiniteRange(style.EndLengthMm, .1, 20) &&
                FiniteRange(style.StartMinimum, 0, 1) && FiniteRange(style.EndMinimum, 0, 1) &&
                FiniteRange(style.TiltWidth, .1, 3) ? style : null;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or ArgumentException) { return null; }
    }
    private static bool FiniteRange(double x, double min, double max) => double.IsFinite(x) && x >= min && x <= max;
    private static bool ValidCurve(List<CurvePoint>? points, double end) => points is { Count: >= 2 and <= 257 } &&
        points.All(p => p != null && FiniteRange(p.X, 0, end) && FiniteRange(p.Y, 0, 3)) && points[0].X == 0 && points[^1].X == end &&
        points.Zip(points.Skip(1)).All(pair => pair.First.X < pair.Second.X);
}

public sealed record NamedPenStyle(string Id, string Name, PenStyle Style, PenConfiguration? Configuration = null)
{
    public override string ToString() => Name;
    public JsonObject ToJson() => new() { ["id"] = Id, ["name"] = Name, ["style"] = Style.ToJson(), ["configuration"] = Configuration?.ToJson() };
    public static NamedPenStyle? FromJson(JsonObject? json)
    {
        try
        {
            if (json?["configuration"] is JsonObject config) return FromConfiguration(PenConfiguration.Parse(config.ToJsonString()));
            var id = json?["id"]?.GetValue<string>(); var name = json?["name"]?.GetValue<string>();
            var style = PenStyle.FromJson(json?["style"] as JsonObject);
            return !string.IsNullOrWhiteSpace(id) && !string.IsNullOrWhiteSpace(name) && name.Length <= 40 && style != null ? new(id, name, style) : null;
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException) { return null; }
    }

    public static IReadOnlyList<NamedPenStyle> Defaults()
    {
        using var stream = typeof(NamedPenStyle).Assembly.GetManifestResourceStream("Mojian.PenStylePresets.json")!;
        return JsonNode.Parse(stream)!.AsArray().OfType<JsonObject>().Select(j => FromConfiguration(PenConfiguration.Parse(j.ToJsonString()))).ToList();
    }

    public static NamedPenStyle FromConfiguration(PenConfiguration config) => new(config.Id, config.Name, config.Resolve(config.Parameters), config);
}
