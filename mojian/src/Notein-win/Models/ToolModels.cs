using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Notein_win.Models;

public enum DrawingToolKind
{
    Pen,
    Highlighter,
    Eraser,
    Lasso,
}

public enum SpringLoadedToolKind
{
    Eraser,
    PanoramaHand,
}

public enum LassoShape
{
    Freeform,
    Rectangle,
}

public enum InkPalette
{
    Black,
    Blue,
    Red,
}

public static class InkPaletteExtensions
{
    public static string GetLabel(this InkPalette palette) => palette switch
    {
        InkPalette.Black => "黑",
        InkPalette.Blue => "蓝",
        InkPalette.Red => "红",
        _ => "黑"
    };

    public static uint GetArgb(this InkPalette palette) => palette switch
    {
        InkPalette.Black => 0xFF1B1B1F,
        InkPalette.Blue => 0xFF185ABD,
        InkPalette.Red => 0xFFC42B1C,
        _ => 0xFF1B1B1F
    };

    public static string GetHex(this InkPalette palette) => palette switch
    {
        InkPalette.Black => "#1B1B1F",
        InkPalette.Blue => "#185ABD",
        InkPalette.Red => "#C42B1C",
        _ => "#1B1B1F"
    };
}

public class ToolPreset
{
    public const float MinimumEraserRadiusMm = 0.25f;
    public const float MaximumEraserRadiusMm = 40f;

    public string Id { get; set; }
    public DrawingToolKind Kind { get; set; }
    public InkPalette Color { get; set; } = InkPalette.Black;
    public float WidthMm { get; set; } = 0.4f;
    public int PressurePercent { get; set; } = 200;
    public int InkFlowPercent { get; set; } = 100;
    public int OpacityPercent { get; set; } = 32;
    public float EraserRadiusMm { get; set; } = 4.5f;
    public bool PressureEraseEnabled { get; set; }
    public int PressureEraseDwellMilliseconds { get; set; } = 140;
    public int PressureEraseThresholdPercent { get; set; } = 78;
    public LassoShape LassoShape { get; set; } = LassoShape.Freeform;

    [JsonIgnore]
    public bool IsSelected { get; set; }

    public ToolPreset(
        string id,
        DrawingToolKind kind,
        InkPalette color = InkPalette.Black,
        float widthMm = 0.4f,
        int pressurePercent = 200,
        int inkFlowPercent = 100,
        int opacityPercent = 32,
        float eraserRadiusMm = 4.5f,
        bool pressureEraseEnabled = false)
    {
        Id = id;
        Kind = kind;
        Color = color;
        WidthMm = widthMm;
        PressurePercent = pressurePercent;
        InkFlowPercent = inkFlowPercent;
        OpacityPercent = opacityPercent;
        EraserRadiusMm = eraserRadiusMm;
        PressureEraseEnabled = pressureEraseEnabled;
    }

    public string DisplayName => Kind switch
    {
        DrawingToolKind.Pen => $"{Color.GetLabel()}色笔",
        DrawingToolKind.Highlighter => $"{Color.GetLabel()}色荧光",
        DrawingToolKind.Eraser => "橡皮",
        DrawingToolKind.Lasso => "套索",
        _ => "绘图工具"
    };

    public string ColorHex => Color.GetHex();

    // Android's displayColor(): logical black remains black in saved ink, but is light in dark chrome/canvas.
    public string DisplayColorHex => Color == InkPalette.Black ? "#EFEFEF" : Color.GetHex();

    public JsonObject ToJson()
    {
        var obj = new JsonObject
        {
            ["id"] = Id,
            ["kind"] = Kind.ToString(),
            ["color"] = Color.ToString(),
            ["widthMm"] = WidthMm,
            ["pressurePercent"] = PressurePercent,
            ["inkFlowPercent"] = InkFlowPercent,
            ["opacityPercent"] = OpacityPercent,
            ["eraserRadiusMm"] = EraserRadiusMm,
            ["pressureEraseEnabled"] = PressureEraseEnabled,
            ["pressureEraseDwellMilliseconds"] = PressureEraseDwellMilliseconds,
            ["pressureEraseThresholdPercent"] = PressureEraseThresholdPercent,
            ["lassoShape"] = LassoShape.ToString()
        };
        return obj;
    }

    public static ToolPreset? FromJson(JsonObject json)
    {
        try
        {
            var id = json["id"]?.GetValue<string>() ?? Guid.NewGuid().ToString();
            var kindStr = json["kind"]?.GetValue<string>() ?? DrawingToolKind.Pen.ToString();
            var kind = Enum.TryParse<DrawingToolKind>(kindStr, true, out var parsedKind) ? parsedKind : DrawingToolKind.Pen;

            var colorStr = json["color"]?.GetValue<string>() ?? InkPalette.Black.ToString();
            var color = Enum.TryParse<InkPalette>(colorStr, true, out var parsedColor) ? parsedColor : InkPalette.Black;

            var widthMm = json["widthMm"]?.GetValue<float>() ?? 0.4f;
            var pressurePercent = json["pressurePercent"]?.GetValue<int>() ?? 200;
            var inkFlowPercent = json["inkFlowPercent"]?.GetValue<int>() ?? 100;
            var opacityPercent = json["opacityPercent"]?.GetValue<int>() ?? 32;
            var eraserRadiusMm = Math.Clamp(
                json["eraserRadiusMm"]?.GetValue<float>() ?? 4.5f,
                MinimumEraserRadiusMm,
                MaximumEraserRadiusMm);
            var pressureEraseEnabled = json["pressureEraseEnabled"]?.GetValue<bool>() ?? false;
            var lassoShapeText = json["lassoShape"]?.GetValue<string>() ?? LassoShape.Freeform.ToString();
            var lassoShape = Enum.TryParse<LassoShape>(lassoShapeText, true, out var parsedLassoShape)
                ? parsedLassoShape
                : LassoShape.Freeform;
            var preset = new ToolPreset(id, kind, color, widthMm, pressurePercent, inkFlowPercent, opacityPercent, eraserRadiusMm, pressureEraseEnabled)
            {
                PressureEraseDwellMilliseconds = Math.Clamp(json["pressureEraseDwellMilliseconds"]?.GetValue<int>() ?? 140, 50, 500),
                PressureEraseThresholdPercent = Math.Clamp(json["pressureEraseThresholdPercent"]?.GetValue<int>() ?? 78, 40, 100),
                LassoShape = lassoShape,
            };
            return preset;
        }
        catch
        {
            return null;
        }
    }
}

public class ToolShelf
{
    private readonly List<ToolPreset> _mutableTools;
    private int _sequence;

    public string SelectedId { get; private set; }

    public IReadOnlyList<ToolPreset> Tools => _mutableTools;

    public ToolPreset Selected =>
        _mutableTools.FirstOrDefault(t => t.Id == SelectedId) ?? _mutableTools[0];

    public ToolShelf(IEnumerable<ToolPreset>? initialTools = null)
    {
        _mutableTools = (initialTools ?? Defaults()).ToList();
        RestoreMissingDefaults();
        _sequence = _mutableTools.Count + 1;
        SelectedId = _mutableTools[0].Id;
    }

    public bool Select(string id)
    {
        if (_mutableTools.All(t => t.Id != id)) return false;
        SelectedId = id;
        return true;
    }

    public ToolPreset Add(DrawingToolKind kind)
    {
        var preset = kind switch
        {
            DrawingToolKind.Pen => new ToolPreset(
                id: NextId("pen"),
                kind: kind,
                color: NextPenColor()
            ),
            DrawingToolKind.Highlighter => new ToolPreset(
                id: NextId("highlighter"),
                kind: kind,
                color: InkPalette.Blue,
                widthMm: 5.5f,
                opacityPercent: 32,
                pressureEraseEnabled: false
            ),
            DrawingToolKind.Eraser => new ToolPreset(
                id: NextId("eraser"),
                kind: kind,
                eraserRadiusMm: 4.5f,
                pressureEraseEnabled: true
            ),
            DrawingToolKind.Lasso => new ToolPreset(
                id: NextId("lasso"),
                kind: kind,
                pressureEraseEnabled: false
            ),
            _ => throw new ArgumentOutOfRangeException(nameof(kind), kind, null)
        };

        _mutableTools.Add(preset);
        SelectedId = preset.Id;
        return preset;
    }

    public void RestoreFrom(ToolShelf other)
    {
        _mutableTools.Clear();
        _mutableTools.AddRange(other._mutableTools);
        RestoreMissingDefaults();
        SelectedId = _mutableTools.Any(t => t.Id == other.SelectedId) ? other.SelectedId : _mutableTools[0].Id;
        _sequence = _mutableTools.Count + 1;
    }

    private void RestoreMissingDefaults()
    {
        var knownIds = _mutableTools.Select(tool => tool.Id).ToHashSet(StringComparer.Ordinal);
        foreach (var preset in Defaults())
        {
            if (knownIds.Add(preset.Id)) _mutableTools.Add(preset);
        }
    }

    public string ToJson()
    {
        var root = new JsonObject
        {
            ["selectedId"] = SelectedId
        };
        var array = new JsonArray();
        foreach (var tool in _mutableTools)
        {
            array.Add(tool.ToJson());
        }
        root["tools"] = array;
        return root.ToJsonString();
    }

    private InkPalette NextPenColor()
    {
        var used = _mutableTools
            .Where(t => t.Kind == DrawingToolKind.Pen)
            .Select(t => t.Color)
            .ToHashSet();

        foreach (var color in Enum.GetValues<InkPalette>())
        {
            if (!used.Contains(color)) return color;
        }
        return InkPalette.Black;
    }

    private string NextId(string prefix) => $"{prefix}-{_sequence++}";

    public static ToolShelf? FromJson(string jsonStr)
    {
        if (string.IsNullOrWhiteSpace(jsonStr)) return null;
        try
        {
            var node = JsonNode.Parse(jsonStr) as JsonObject;
            if (node == null) return null;

            var array = node["tools"] as JsonArray;
            if (array == null) return null;

            var list = new List<ToolPreset>();
            foreach (var item in array)
            {
                if (item is JsonObject obj)
                {
                    var preset = ToolPreset.FromJson(obj);
                    if (preset != null) list.Add(preset);
                }
            }

            if (list.Count == 0) return null;

            var shelf = new ToolShelf(list);
            var selectedId = node["selectedId"]?.GetValue<string>();
            if (!string.IsNullOrEmpty(selectedId) && list.Any(t => t.Id == selectedId))
            {
                shelf.Select(selectedId);
            }
            return shelf;
        }
        catch
        {
            return null;
        }
    }

    public static IEnumerable<ToolPreset> Defaults() => new List<ToolPreset>
    {
        new("pen-black", DrawingToolKind.Pen, InkPalette.Black),
        new("pen-blue", DrawingToolKind.Pen, InkPalette.Blue),
        new("pen-red", DrawingToolKind.Pen, InkPalette.Red),
        new(
            id: "highlighter-blue",
            kind: DrawingToolKind.Highlighter,
            color: InkPalette.Blue,
            widthMm: 5.5f,
            opacityPercent: 32,
            pressureEraseEnabled: false
        ),
        new(
            id: "eraser-stroke",
            kind: DrawingToolKind.Eraser,
            eraserRadiusMm: 4.5f,
            pressureEraseEnabled: true
        ),
        new(
            id: "lasso-freeform",
            kind: DrawingToolKind.Lasso,
            pressureEraseEnabled: false
        )
    };
}
