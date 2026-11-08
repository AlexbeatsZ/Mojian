using System.Globalization;
using System.Text.Json.Nodes;

namespace Notein_win.Models;

public sealed record PenParameterControl(string Parameter, string Label, double Min, double Max, double Step, string Unit, string? LegacyField)
{
    public string Format(double value) => value.ToString(Step < 1 ? "0.##" : "0", CultureInfo.CurrentCulture) + Unit;
}

// Authoring program only. Finished ink still owns a resolved PenStyle and canonical outline.
public sealed class PenConfiguration
{
    public const string Format = "mojian-pen/v1";
    public const int MaximumBytes = 131072;
    private readonly JsonObject _json;
    public string Id { get; }
    public string Name { get; }
    public IReadOnlyDictionary<string, double> Parameters { get; }
    public IReadOnlyList<PenParameterControl> Controls { get; }
    public string Text => _json.ToJsonString(new() { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
    public JsonObject ToJson() => (JsonObject)_json.DeepClone();

    private PenConfiguration(JsonObject json)
    {
        _json = (JsonObject)json.DeepClone();
        Require(json["format"]?.GetValue<string>() == Format, "format 必须为 mojian-pen/v1。");
        Require(json.All(p => new[] { "$schema", "format", "id", "name", "parameters", "controls", "style" }.Contains(p.Key)), "配置含未知字段。");
        Id = json["id"]!.GetValue<string>(); Name = json["name"]!.GetValue<string>().Trim();
        Require(Id.Length is >= 1 and <= 80 && Id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.'), "id 只能使用字母、数字、点、横线和下划线。");
        Require(Name.Length is >= 1 and <= 40, "名称须为 1–40 个字符。");
        var parameters = (json["parameters"] as JsonObject ?? new()).ToDictionary(p => p.Key, p => p.Value!.GetValue<double>());
        parameters.TryAdd("widthMm", .4);
        Require(parameters.Count <= 32 && parameters.All(p => p.Key.Length is >= 1 and <= 40 && p.Key.All(c => char.IsAsciiLetterOrDigit(c) || c == '_') && double.IsFinite(p.Value)), "parameters 无效。");
        Parameters = parameters;
        var controls = new List<PenParameterControl>();
        foreach (var node in json["controls"]?.AsArray() ?? new())
        {
            var c = node!.AsObject();
            Require(c.All(p => new[] { "parameter", "label", "min", "max", "step", "unit", "legacyField" }.Contains(p.Key)), "调节项含未知字段。");
            var control = new PenParameterControl(c["parameter"]!.GetValue<string>(), c["label"]!.GetValue<string>(), c["min"]!.GetValue<double>(), c["max"]!.GetValue<double>(), c["step"]!.GetValue<double>(), c["unit"]?.GetValue<string>() ?? "", c["legacyField"]?.GetValue<string>());
            Require(control.Parameter != "widthMm", "widthMm 是当前工具笔宽，不可重复添加调节项。");
            Require(parameters.ContainsKey(control.Parameter) && control.Label.Length is >= 1 and <= 20 && control.Unit.Length <= 12 && double.IsFinite(control.Min) && double.IsFinite(control.Max) && double.IsFinite(control.Step) && control.Min < control.Max && control.Step > 0 && (control.Max - control.Min) / control.Step <= 10000, "调节项的参数、名称或范围无效。");
            Require(parameters[control.Parameter] >= control.Min && parameters[control.Parameter] <= control.Max, "调节项默认值超出范围。");
            Require(control.LegacyField is null or "pressurePercent" or "inkFlowPercent" or "stabilityPercent", "legacyField 无效。");
            controls.Add(control);
        }
        Require(controls.Count <= 12 && controls.Select(c => c.Parameter).Distinct().Count() == controls.Count && controls.Where(c => c.LegacyField != null).Select(c => c.LegacyField).Distinct().Count() == controls.Count(c => c.LegacyField != null), "调节项重复或超过 12 项。");
        Controls = controls;
        _ = Resolve(parameters);
    }

    public static PenConfiguration Parse(string text)
    {
        try
        {
            Require(System.Text.Encoding.UTF8.GetByteCount(text) <= MaximumBytes, "配置文件不能超过 128 KiB。");
            return new PenConfiguration(JsonNode.Parse(text, documentOptions: new() { MaxDepth = 32 })!.AsObject());
        }
        catch (Exception error) when (error is not ArgumentException)
        { throw new ArgumentException("配置格式无效：" + error.Message, error); }
    }

    public static PenConfiguration FromStyle(NamedPenStyle preset)
    {
        JsonObject ConvertCurve(List<CurvePoint> points) => new()
        {
            ["breaks"] = new JsonArray(points.Select(p => (JsonNode)JsonValue.Create(p.X)!).ToArray()),
            ["function"] = new JsonObject { ["op"] = "piecewise", ["segments"] = new JsonArray(points.Zip(points.Skip(1)).Select(pair => (JsonNode)new JsonObject
            {
                ["from"] = pair.First.X, ["to"] = pair.Second.X,
                ["value"] = new JsonObject { ["op"] = "linear", ["args"] = new JsonArray("x", pair.First.X, pair.Second.X, pair.First.Y, pair.Second.Y) }
            }).ToArray()) }
        };
        var style = preset.Style.ToJson(); style.Remove("version");
        style["pressureCurve"] = ConvertCurve(preset.Style.PressureCurve); style["speedCurve"] = ConvertCurve(preset.Style.SpeedCurve); style["stabilityPercent"] = "$stability";
        return Parse(new JsonObject
        {
            ["format"] = Format, ["id"] = preset.Id, ["name"] = preset.Name,
            ["parameters"] = new JsonObject { ["stability"] = preset.Style.StabilityPercent },
            ["controls"] = new JsonArray(new JsonObject { ["parameter"] = "stability", ["label"] = "稳定性", ["min"] = 0, ["max"] = 100, ["step"] = 1, ["unit"] = "%", ["legacyField"] = "stabilityPercent" }),
            ["style"] = style,
        }.ToJsonString());
    }

    public Dictionary<string, double> ValuesFor(ToolPreset tool)
    {
        var values = Parameters.ToDictionary(p => p.Key, p => p.Value);
        if (tool.PenParameterValues.TryGetValue(Id, out var saved))
            foreach (var (key, value) in saved) if (values.ContainsKey(key) && double.IsFinite(value)) values[key] = value;
        values["widthMm"] = tool.WidthMm;
        // These bindings deliberately retain the user's original per-tool percentages.
        foreach (var c in Controls)
        {
            if (c.LegacyField != null) values[c.Parameter] = c.LegacyField switch
            { "pressurePercent" => tool.PressurePercent, "inkFlowPercent" => tool.InkFlowPercent, _ => tool.StabilityPercent };
            values[c.Parameter] = Math.Clamp(values[c.Parameter], c.Min, c.Max);
        }
        return values;
    }

    public void SetValue(ToolPreset tool, string parameter, double value)
    {
        var c = Controls.Single(p => p.Parameter == parameter);
        Require(double.IsFinite(value) && value >= c.Min && value <= c.Max, "参数超出范围。");
        var values = ValuesFor(tool); values[parameter] = value;
        var resolved = Resolve(values); // Validate before changing any live state.
        tool.PenParameterValues[Id] = values;
        if (c.LegacyField == "pressurePercent") tool.PressurePercent = (int)Math.Round(value);
        if (c.LegacyField == "inkFlowPercent") tool.InkFlowPercent = (int)Math.Round(value);
        if (c.LegacyField == "stabilityPercent") tool.StabilityPercent = (int)Math.Round(value);
        tool.PenStyle = resolved;
    }

    public PenStyle Resolve(IReadOnlyDictionary<string, double> parameters)
    {
        var source = _json["style"]!.AsObject();
        var result = PenStyle.FromLegacy().ToJson();
        var speedMaximum = source["speedMaximumCmPerSecond"] is { } maximum ? Evaluate(maximum, parameters, 0) : 64;
        foreach (var (key, value) in source)
        {
            Require(result.ContainsKey(key) && key != "version", "不支持的笔触参数：" + key);
            if (key is "pressureCurve" or "speedCurve")
            {
                var end = key == "pressureCurve" ? 1d : speedMaximum;
                result[key] = Curve(value!.AsObject(), parameters, end);
            }
            else if (result[key] is JsonValue v && v.TryGetValue<bool>(out _)) result[key] = value!.GetValue<bool>();
            else
            {
                var number = Evaluate(value!, parameters, 0);
                result[key] = key.EndsWith("Ms", StringComparison.Ordinal) || key is "stabilityPercent" or "upsamplingHz" ? JsonValue.Create((int)Math.Floor(number + .5)) : JsonValue.Create(number);
            }
        }
        return PenStyle.FromJson(result) ?? throw new ArgumentException("笔触参数超出引擎支持范围。");
    }

    private static JsonArray Curve(JsonObject curve, IReadOnlyDictionary<string, double> parameters, double end)
    {
        Require(curve.All(p => new[] { "function", "samples", "breaks" }.Contains(p.Key)) && curve.ContainsKey("function"), "曲线须使用 function、samples、breaks，不使用控制点。");
        var n = curve["samples"]?.GetValue<int>() ?? 128;
        Require(n is >= 0 and <= 256, "samples 必须在 0–256 之间。");
        var xs = new SortedSet<double> { 0, end };
        void Boundaries(JsonNode? node)
        {
            if (node is JsonObject obj)
            {
                if (obj["op"]?.GetValue<string>() == "piecewise" && obj["segments"] is JsonArray segments)
                    foreach (var s in segments.OfType<JsonObject>()) foreach (var key in new[] { "from", "to" })
                        if (s[key] is { } v && v.GetValue<double>() is var x && x >= 0 && x <= end) xs.Add(x);
                foreach (var child in obj) Boundaries(child.Value);
            }
            else if (node is JsonArray array) foreach (var child in array) Boundaries(child);
        }
        Boundaries(curve["function"]);
        if (n > 0) for (var i = 1; i < n; i++) xs.Add(end * i / n);
        foreach (var x in curve["breaks"]?.AsArray() ?? new())
        { var number = x!.GetValue<double>(); Require(double.IsFinite(number) && number >= 0 && number <= end, "breaks 超出曲线定义域。"); xs.Add(number); }
        Require(xs.Count <= 257, "曲线采样超过 257 项。");
        var points = new JsonArray();
        foreach (var x in xs)
        {
            var y = Evaluate(curve["function"]!, parameters, x);
            Require(y is >= 0 and <= 3, "曲线宽度倍率必须在 0–3 之间。");
            points.Add(new JsonObject { ["x"] = x, ["y"] = y });
        }
        return points;
    }

    // A bounded mathematical data tree, never an executable script or host-language eval.
    public static double Evaluate(JsonNode expression, IReadOnlyDictionary<string, double> parameters, double x, int depth = 0)
    {
        Require(depth <= 20, "函数嵌套超过 20 层。");
        if (expression is JsonValue value)
        {
            if (value.TryGetValue<double>(out var number)) { Require(double.IsFinite(number), "数字必须有限。"); return number; }
            var name = value.GetValue<string>();
            if (name == "x") return x;
            Require(name.StartsWith('$') && parameters.ContainsKey(name[1..]), "未知函数变量：" + name);
            return parameters[name[1..]];
        }
        var node = expression.AsObject();
        var op = node["op"]!.GetValue<string>();
        Require(node.All(p => p.Key == "op" || p.Key == (op == "piecewise" ? "segments" : "args")) && node.ContainsKey(op == "piecewise" ? "segments" : "args"), "函数含未知或缺失字段。");
        var a = (node["args"]?.AsArray() ?? new()).Select(v => Evaluate(v!, parameters, x, depth + 1)).ToArray();
        Require(a.Length <= 32, "函数最多支持 32 个参数。");
        double Arity(int n, Func<double> action) { Require(a.Length == n, op + " 参数数量错误。"); return action(); }
        var result = op switch
        {
            "add" => a.Sum(), "multiply" => a.Aggregate(1d, (p, q) => p * q),
            "subtract" => Arity(2, () => a[0] - a[1]), "divide" => Arity(2, () => a[0] / a[1]),
            "pow" => Arity(2, () => Math.Pow(a[0], a[1])), "min" => Arity(2, () => Math.Min(a[0], a[1])), "max" => Arity(2, () => Math.Max(a[0], a[1])),
            "abs" => Arity(1, () => Math.Abs(a[0])), "sin" => Arity(1, () => Math.Sin(a[0])), "cos" => Arity(1, () => Math.Cos(a[0])),
            "exp" => Arity(1, () => Math.Exp(a[0])), "log" => Arity(1, () => Math.Log(a[0])),
            "clamp" => Arity(3, () => Math.Clamp(a[0], a[1], a[2])),
            "linear" or "smoothstep" => Arity(5, () => {
                Require(a[1] != a[2], "函数区间不能为零。");
                var t = Math.Clamp((a[0] - a[1]) / (a[2] - a[1]), 0, 1);
                if (op == "smoothstep") t = t * t * (3 - 2 * t);
                return a[3] + (a[4] - a[3]) * t;
            }),
            "piecewise" => Piecewise(node["segments"]!.AsArray(), parameters, x, depth),
            _ => throw new ArgumentException("不支持的函数：" + op),
        };
        Require(double.IsFinite(result), "函数计算产生了非有限值。"); return result;
    }

    private static double Piecewise(JsonArray segments, IReadOnlyDictionary<string, double> parameters, double x, int depth)
    {
        Require(segments.Count is >= 1 and <= 32, "分段函数须有 1–32 段。");
        JsonObject? selected = null; double? previous = null;
        foreach (var item in segments)
        {
            var segment = item!.AsObject(); var from = segment["from"]!.GetValue<double>(); var to = segment["to"]!.GetValue<double>();
            Require(segment.All(p => p.Key is "from" or "to" or "value"), "分段函数含未知字段。");
            Require(double.IsFinite(from) && double.IsFinite(to) && from < to && (previous == null || from == previous), "分段函数的区间必须递增且连续。");
            previous = to;
            // Parse all branches, including ones outside the sampled domain.
            _ = Evaluate(segment["value"]!, parameters, Math.Clamp(x, from, to), depth + 1);
            if (selected == null && x <= to) selected = segment;
        }
        selected ??= segments[^1]!.AsObject();
        return Evaluate(selected["value"]!, parameters, Math.Clamp(x, selected["from"]!.GetValue<double>(), selected["to"]!.GetValue<double>()), depth + 1);
    }
    private static void Require(bool condition, string message) { if (!condition) throw new ArgumentException(message); }
}
