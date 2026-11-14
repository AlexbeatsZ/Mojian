using System.Text.Json;
using System.Text.Json.Serialization;
using Notein_win.Models;

namespace Notein_win.Services;

// Untouched historical strokes retain their original fields and numeric tokens. Comparing
// typed values avoids serializing large point arrays or vector outlines to detect an edit.
public sealed class StrokeJsonConverter : JsonConverter<StrokeRecord>
{
    private static readonly JsonSerializerOptions ModelOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };

    public override StrokeRecord Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
    {
        using var json = JsonDocument.ParseValue(ref reader);
        var stroke = json.RootElement.Deserialize<StrokeRecord>(ModelOptions) ?? throw new JsonException("笔迹数据无效。");
        stroke.SerializationSource = new StrokeSerializationSource(System.Text.Encoding.UTF8.GetBytes(json.RootElement.GetRawText()), stroke.CopyForSave());
        return stroke;
    }

    public override void Write(Utf8JsonWriter writer, StrokeRecord value, JsonSerializerOptions options)
    {
        if (value.SerializationSource is { } source && SameMetadata(source.Baseline, value) &&
            source.Baseline.Points.Count == value.Points.Count && source.Baseline.Points.Zip(value.Points).All(pair => SamePoint(pair.First, pair.Second)))
        {
            writer.WriteRawValue(source.Json, skipInputValidation: true);
            return;
        }
        JsonSerializer.Serialize(writer, value, ModelOptions);
    }

    internal static bool SameMetadata(StrokeRecord first, StrokeRecord second) =>
        first.Id == second.Id && first.Kind == second.Kind && first.Color == second.Color && first.WidthMm == second.WidthMm &&
        first.PressurePercent == second.PressurePercent && first.InkFlowPercent == second.InkFlowPercent &&
        first.StabilityPercent == second.StabilityPercent && first.StrokeUnitLengthCm == second.StrokeUnitLengthCm &&
        first.OpacityPercent == second.OpacityPercent && first.BrushProfile == second.BrushProfile && first.ColorHex == second.ColorHex &&
        first.LayerId == second.LayerId && first.ZOrder == second.ZOrder && first.VectorPath == second.VectorPath &&
        SameArray(first.VectorBounds, second.VectorBounds) && SameArray(first.VectorTransform, second.VectorTransform) &&
        SameStyle(first.PenStyle, second.PenStyle) && SameJson(first.SourceBrush, second.SourceBrush) &&
        SameAdditionalData(first.AdditionalData, second.AdditionalData);

    private static bool SameStyle(PenStyle? first, PenStyle? second) => ReferenceEquals(first, second) ||
        first != null && second != null && first.PressureCurve.SequenceEqual(second.PressureCurve) && first.SpeedCurve.SequenceEqual(second.SpeedCurve) &&
        first with { PressureCurve = second.PressureCurve, SpeedCurve = second.SpeedCurve } == second;
    private static bool SameArray(double[]? first, double[]? second) => ReferenceEquals(first, second) ||
        first != null && second != null && first.AsSpan().SequenceEqual(second);
    private static bool SameJson(JsonElement? first, JsonElement? second) => first == null ? second == null :
        second != null && JsonElement.DeepEquals(first.Value, second.Value);
    private static bool SamePoint(InkPoint first, InkPoint second) =>
        first.X == second.X && first.Y == second.Y && first.Pressure == second.Pressure &&
        first.TiltX == second.TiltX && first.TiltY == second.TiltY && first.TiltRadians == second.TiltRadians &&
        first.OrientationRadians == second.OrientationRadians && first.ElapsedMilliseconds == second.ElapsedMilliseconds &&
        SameAdditionalData(first.AdditionalData, second.AdditionalData);
    private static bool SameAdditionalData(Dictionary<string, JsonElement>? first, Dictionary<string, JsonElement>? second) =>
        ReferenceEquals(first, second) || first != null && second != null && first.Count == second.Count &&
        first.All(pair => second.TryGetValue(pair.Key, out var value) && JsonElement.DeepEquals(pair.Value, value));
}

internal sealed record StrokeSerializationSource(byte[] Json, StrokeRecord Baseline);
