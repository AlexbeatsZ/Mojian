using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public sealed class PenStyleTests
{
    private static JsonObject Config(string id = "test", string name = "测试笔触")
    { var json = NamedPenStyle.Defaults().First(p => p.Id == "notein").Configuration!.ToJson(); json["id"] = id; json["name"] = name; return json; }

    [Fact]
    public void NoteinAndOneNoteArePreinstalledAndOriginalPercentagesStillControlTheCurve()
    {
        var old = new ToolPreset("pen-black", DrawingToolKind.Pen) { WidthMm = .3f, PressurePercent = 78, InkFlowPercent = 20, StabilityPercent = 0 };
        var shelf = ToolShelf.FromJson(new JsonObject { ["tools"] = new JsonArray(old.ToJson()) }.ToJsonString())!;
        Assert.Equal(["Notein风格", "Onenote风格"], shelf.PenStyles.Select(p => p.Name)); Assert.Equal("notein", old.PenStyleId == "" ? shelf.Selected.PenStyleId : old.PenStyleId);
        var tool = shelf.Selected;
        Assert.Equal(.3f, tool.WidthMm); Assert.Equal(78, tool.PressurePercent); Assert.Equal(20, tool.InkFlowPercent); Assert.Equal(0, tool.StabilityPercent);
        var expected = PenStyle.FromLegacy(78, 20, 0);
        Assert.Equal(expected.Key(), tool.PenStyle!.Key());
        shelf.SetPenParameter(tool, "pressure", 180); shelf.SetPenParameter(tool, "flow", 150); shelf.SetPenParameter(tool, "stability", 75);
        Assert.Equal(PenStyle.FromLegacy(180, 150, 75).Key(), tool.PenStyle!.Key());
        var restored = ToolShelf.FromJson(shelf.ToJson())!;
        Assert.Equal(tool.PenStyle.Key(), restored.Selected.PenStyle!.Key());
    }

    [Theory]
    [InlineData(0, 0, 0)] [InlineData(78, 20, 0)] [InlineData(200, 100, 50)] [InlineData(400, 200, 100)]
    public void NoteinConfigurationMatchesThePreviousActualV2Model(int pressure, int flow, int stability)
    {
        var config = NamedPenStyle.Defaults().First(p => p.Id == "notein").Configuration!;
        var tool = new ToolPreset("p", DrawingToolKind.Pen) { PressurePercent = pressure, InkFlowPercent = flow, StabilityPercent = stability };
        var stroke = new StrokeRecord { BrushProfile = FountainV2Model.Profile, WidthMm = .3f, PressurePercent = pressure, InkFlowPercent = flow, StabilityPercent = stability, StrokeUnitLengthCm = .015,
            Points = Enumerable.Range(0, 80).Select(i => new InkPoint(i * 2, 30 + (float)Math.Sin(i * .2) * 12, (float)(.3 + .3 * Math.Sin(i * .1)), elapsedMilliseconds: i * 7)).ToList() };
        var original = FountainV2Model.Model(stroke);
        stroke.PenStyle = config.Resolve(config.ValuesFor(tool)); stroke.BrushProfile = PenStyle.Profile;
        var resolved = FountainV2Model.Model(stroke);
        Assert.Equal(original.Count, resolved.Count);
        for (var i = 0; i < original.Count; i++) { Assert.Equal(original[i].Position, resolved[i].Position); Assert.Equal(original[i].Width, resolved[i].Width, 6); }
    }

    [Fact]
    public void OneNoteConfigurationMatchesMeasuredEllipseTipPressureModel()
    {
        // Measured from desktop OneNote 16 synthetic pen strokes: ellipse tip, raw points, width × (0.25 + 1.5p).
        using var example = typeof(PenStyleTests).Assembly.GetManifestResourceStream("PenConfiguration.OneNoteExample.json")!;
        var config = NamedPenStyle.Defaults().Single(p => p.Id == "onenote").Configuration!;
        Assert.Equal("Onenote风格", config.Name);
        Assert.True(JsonNode.DeepEquals(JsonNode.Parse(example), config.ToJson()));
        var tool = new ToolPreset("p", DrawingToolKind.Pen) { WidthMm = .35f, PressurePercent = 200, InkFlowPercent = 100, StabilityPercent = 50 };
        var style = config.Resolve(config.ValuesFor(tool));
        Assert.True(style.PressureEnabled); Assert.Equal(0, style.StabilityPercent); Assert.Equal(0, style.PressureResponseMs);
        Assert.False(style.SpeedEnabled || style.DirectionEnabled || style.StartEnabled || style.EndEnabled || style.TiltEnabled || style.TurnEnabled);
        foreach (var p in new[] { 0d, .02, .1, .5, .73, 1d }) Assert.Equal(.25 + 1.5 * p, PenStyle.Evaluate(style.PressureCurve, p), 9);
        var flat = config.Resolve(new Dictionary<string, double>(config.ValuesFor(tool)) { ["pressure"] = 0 });
        foreach (var p in new[] { 0d, .5, 1d }) Assert.Equal(1, PenStyle.Evaluate(flat.PressureCurve, p), 9);
        foreach (var p in new[] { .1, .5, 1d })
        {
            var stroke = new StrokeRecord { BrushProfile = PenStyle.Profile, PenStyle = style, WidthMm = .35f, StrokeUnitLengthCm = .015,
                Points = Enumerable.Range(0, 60).Select(i => new InkPoint(i * (i % 7 + 1), 40, (float)p, elapsedMilliseconds: i * 4)).ToList() };
            Assert.All(FountainV2Model.Model(stroke), s => Assert.Equal(.35 * 96 / 25.4 * (.25 + 1.5 * (float)p), s.Width, 5));
        }
        Assert.Equal(200, tool.PressurePercent); Assert.Equal(50, tool.StabilityPercent);
    }

    [Fact]
    public void OldAndroidResponseCanBeDescribedByFunctionsAndEngineParameters()
    {
        using var source = typeof(PenStyleTests).Assembly.GetManifestResourceStream("PenConfiguration.LegacyExample.json")!;
        var config = PenConfiguration.Parse(new StreamReader(source).ReadToEnd());
        var tool = new ToolPreset("pen", DrawingToolKind.Pen) { WidthMm = .3f, PressurePercent = 78, InkFlowPercent = 20 };
        var resolved = config.Resolve(config.ValuesFor(tool));
        Assert.Equal(85, resolved.SpeedMaximumCmPerSecond); Assert.Equal(20, resolved.InputWindowMs);
        Assert.Equal(tool.WidthMm * 1.25, resolved.StartLengthMm, 6); Assert.Equal(tool.WidthMm * 2.5, resolved.EndLengthMm, 6);
        Assert.True(resolved.TurnEnabled); Assert.Equal(12, resolved.StartResponseMs); Assert.Equal(12, resolved.EndResponseMs);
        Assert.Equal(20, resolved.PressureResponseMs); Assert.Equal(18, resolved.SpeedResponseMs);
        foreach (var p in new[] { 0d, .18, .5, .88, 1d })
            Assert.Equal(1 + .45 * .39 * (2 * Math.Clamp((p - .18) / .7, 0, 1) - 1), PenStyle.Evaluate(resolved.PressureCurve, p), 7);
        Assert.Equal(1 + .08 * .2, PenStyle.Evaluate(resolved.SpeedCurve, 0), 7); Assert.Equal(1 - .18 * .2, PenStyle.Evaluate(resolved.SpeedCurve, 85), 7);
        var shelf = new ToolShelf([tool]); shelf.ApplyPenStyle(tool, shelf.ImportPenConfiguration(config.Text)); shelf.SetPenParameter(tool, "windowMs", 80);
        Assert.Equal(80, tool.PenStyle!.InputWindowMs); Assert.Equal(50, tool.StabilityPercent);
    }

    [Fact]
    public void ImportUpdatesByIdPreservesKnobsAndCannotChangeHistoricalInk()
    {
        var shelf = new ToolShelf(); var tool = shelf.Selected;
        var json = Config(); json["controls"]![0]!["label"] = "压力曲率";
        var imported = shelf.ImportPenConfiguration(json.ToJsonString()); shelf.ApplyPenStyle(tool, imported);
        shelf.SetPenParameter(tool, "pressure", 78); shelf.SetPenParameter(tool, "stability", 0);
        var historical = new StrokeRecord { BrushProfile = PenStyle.Profile, PenStyle = tool.PenStyle!.Copy() };
        json["style"]!["endEnabled"] = true;
        shelf.ImportPenConfiguration(json.ToJsonString());
        Assert.True(tool.PenStyle!.EndEnabled); Assert.False(historical.PenStyle!.EndEnabled); Assert.Equal(78, tool.PressurePercent); Assert.Equal(0, tool.StabilityPercent);
        shelf.ApplyPenStyle(tool, shelf.PenStyles.Single(p => p.Id == "notein"));
        shelf.ApplyPenStyle(tool, shelf.PenStyles.Single(p => p.Id == imported.Id));
        Assert.Equal(78, tool.PressurePercent); Assert.Equal("压力曲率", shelf.PenControls(tool)[0].Label);
        var restored = ToolShelf.FromJson(shelf.ToJson())!;
        Assert.Equal(imported.Id, restored.Selected.PenStyleId); Assert.True(restored.Selected.PenStyle!.EndEnabled);
        var before = shelf.ToJson(); json["id"] = "duplicate";
        Assert.Throws<ArgumentException>(() => shelf.ImportPenConfiguration(json.ToJsonString())); Assert.Equal(before, shelf.ToJson());
    }

    [Fact]
    public void SmoothFunctionsAndParameterBindingsWorkWithoutEditablePoints()
    {
        var json = Config();
        json["style"]!["pressureCurve"] = new JsonObject { ["samples"] = 256, ["function"] = new JsonObject { ["op"] = "smoothstep", ["args"] = new JsonArray("x", 0, 1, 0, 1.4) } };
        json["controls"] = new JsonArray(new JsonObject { ["parameter"] = "stability", ["label"] = "速度平滑", ["min"] = 0, ["max"] = 100, ["step"] = 1, ["unit"] = " ms" });
        json["style"]!["speedResponseMs"] = "$stability"; json["style"]!["stabilityPercent"] = 0;
        var shelf = new ToolShelf(); shelf.ApplyPenStyle(shelf.Selected, shelf.ImportPenConfiguration(json.ToJsonString()));
        shelf.SetPenParameter(shelf.Selected, "stability", 80);
        Assert.Equal(80, shelf.Selected.PenStyle!.SpeedResponseMs); Assert.Equal(0, shelf.Selected.PenStyle.StabilityPercent);
        for (var i = 0; i <= 1000; i++) { var x = i / 1000d; Assert.InRange(Math.Abs(PenStyle.Evaluate(shelf.Selected.PenStyle.PressureCurve, x) - 1.4 * x * x * (3 - 2 * x)), 0, .00002); }
        var invalid = Config(); invalid["style"]!["pressureCurve"] = new JsonObject { ["function"] = "$unknown" };
        var before = shelf.ToJson(); Assert.Throws<ArgumentException>(() => shelf.ImportPenConfiguration(invalid.ToJsonString())); Assert.Equal(before, shelf.ToJson());
        invalid = Config(); invalid["style"]!["unsupportedEffect"] = 1;
        Assert.Throws<ArgumentException>(() => PenConfiguration.Parse(invalid.ToJsonString()));
        Assert.Throws<ArgumentException>(() => PenConfiguration.Parse(new string(' ', PenConfiguration.MaximumBytes + 1)));
    }

    [Fact]
    public void PreviousGeneratedPresetsAreRemovedButUserChangesSurvive()
    {
        using var stream = typeof(NamedPenStyle).Assembly.GetManifestResourceStream("Mojian.PenStyleMigration.json")!;
        var old = JsonNode.Parse(stream)!.AsArray();
        var changed = old[0]!.DeepClone(); changed["style"]!["endEnabled"] = true;
        var shelf = new ToolShelf(); var root = JsonNode.Parse(shelf.ToJson())!;
        root["penStyles"] = old.DeepClone(); root["tools"]![0]!["penStyleId"] = "classic";
        var migrated = ToolShelf.FromJson(root.ToJsonString())!;
        Assert.Equal(["notein", "onenote"], migrated.PenStyles.Select(p => p.Id)); Assert.Equal("notein", migrated.Selected.PenStyleId);
        root["penStyles"]!.AsArray().Add(changed);
        Assert.Contains(ToolShelf.FromJson(root.ToJsonString())!.PenStyles, p => p.Id == "natural" && p.Style.EndEnabled);
    }

    [Fact]
    public void FileSelectionOnlyFillsTextAndInvalidImportOrCancelHasNoSideEffects() => WpfTestExecutor.Run(() =>
    {
        LoadTheme(); var shelf = new ToolShelf(); var before = shelf.ToJson();
        var path = Path.Combine(Path.GetTempPath(), ".agents", "mojian-pen-styles", "import-fixture.json"); Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var text = Config().ToJsonString(); File.WriteAllText(path, text);
        try
        {
            var dialog = new PenStyleDialog(shelf, shelf.Selected); dialog.LoadFile(path); Assert.Equal(text, dialog.ConfigurationText); Assert.Equal(before, shelf.ToJson());
            dialog.ConfigurationText = "invalid"; dialog.Import(); Assert.Null(dialog.SavedPreset); Assert.Equal(before, shelf.ToJson());
            dialog.ConfigurationText = NamedPenStyle.Defaults().First(p => p.Id == "notein").Configuration!.Text;
            dialog.Show(); dialog.UpdateLayout(); Capture(dialog, "MOJIAN_PEN_STYLE_SCREENSHOT"); dialog.Close(); Assert.Equal(before, shelf.ToJson());
            var importing = new PenStyleDialog(shelf, shelf.Selected) { ConfigurationText = text };
            importing.Dispatcher.BeginInvoke(importing.Import, DispatcherPriority.Loaded); Assert.True(importing.ShowDialog());
            Assert.Equal("test", shelf.Selected.PenStyleId);
        }
        finally { File.Delete(path); }
    });

    [Fact]
    public void RealFlyoutKeepsKnobsAndNestedDropdownClickDoesNotCloseItsOwner() => WpfTestExecutor.Run(() =>
    {
        LoadTheme(); var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-pen-styles", "fixture-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        MainWindow? window = null;
        try
        {
            var prefs = new PreferencesStore(Path.Combine(root, "preferences.json")); var model = new MainViewModel(root, prefs); model.AddPage("fixture", new PageAppearance()); model.SaveAll();
            model.SelectedTool.WidthMm = .3f;
            var custom = Config(); custom["style"]!["endEnabled"] = true; model.ToolShelf.ImportPenConfiguration(custom.ToJsonString());
            window = new MainWindow(model); window.Show(); window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
            var popup = (Popup)window.FindName("ToolFlyoutPopup"); popup.PlacementTarget = window; model.IsToolFlyoutOpen = true;
            var selector = (ComboBox)window.FindName("PenStyleSelector"); selector.IsDropDownOpen = true; window.UpdateLayout(); window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
            var testIndex = model.ToolShelf.PenStyles.ToList().FindIndex(p => p.Id == "test"); Assert.Equal(2, testIndex);
            var item = (ComboBoxItem)selector.ItemContainerGenerator.ContainerFromIndex(testIndex);
            Assert.NotNull(item);
            var down = new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Left) { RoutedEvent = Mouse.PreviewMouseDownEvent, Source = item };
            typeof(MainWindow).GetMethod("OnWindowPreviewMouseDown", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(window, [window, down]);
            Assert.True(model.IsToolFlyoutOpen);
            selector.SelectedIndex = testIndex; selector.IsDropDownOpen = false;
            Assert.Equal("test", model.SelectedTool.PenStyleId); Assert.True(model.SelectedTool.PenStyle!.EndEnabled);
            selector.SelectedValue = "onenote"; Assert.Equal("onenote", model.SelectedTool.PenStyleId);
            Assert.Equal(["pressure", "stability"], ((StackPanel)window.FindName("PenParameterPanel")).Children.OfType<Slider>().Select(s => (string)s.Tag));
            Assert.Equal(1.75, PenStyle.Evaluate(model.SelectedTool.PenStyle!.PressureCurve, 1), 9);
            selector.SelectedIndex = 0; Assert.Equal("notein", model.SelectedTool.PenStyleId);
            var sliders = ((StackPanel)window.FindName("PenParameterPanel")).Children.OfType<Slider>().ToList(); Assert.Equal(3, sliders.Count);
            sliders.Single(s => Equals(s.Tag, "pressure")).Value = 78; sliders.Single(s => Equals(s.Tag, "flow")).Value = 20; sliders.Single(s => Equals(s.Tag, "stability")).Value = 0;
            Assert.Equal(PenStyle.FromLegacy(78, 20, 0).Key(), model.SelectedTool.PenStyle!.Key());
            Capture((FrameworkElement)popup.Child, "MOJIAN_PEN_TOOL_SCREENSHOT");
            var invalidAt100 = Config("singular", "无效参数回归");
            invalidAt100["style"]!["speedResponseMs"] = new JsonObject { ["op"] = "divide", ["args"] = new JsonArray(100, new JsonObject { ["op"] = "subtract", ["args"] = new JsonArray("$pressure", 100) }) };
            model.SelectedTool.PressurePercent = 200;
            var singular = model.ToolShelf.ImportPenConfiguration(invalidAt100.ToJsonString()); model.SaveToolSettings(); selector.SelectedValue = singular.Id;
            var singularSlider = ((StackPanel)window.FindName("PenParameterPanel")).Children.OfType<Slider>().Single(s => Equals(s.Tag, "pressure"));
            singularSlider.Value = 100; Assert.Equal(200, singularSlider.Value); Assert.Equal(200, model.SelectedTool.PressurePercent); Assert.NotNull(singularSlider.ToolTip);
            selector.SelectedValue = "notein"; model.ToolShelf.SetPenParameter(model.SelectedTool, "pressure", 78); model.SaveToolSettings();
            window.Close(); window = null;
            var restored = new MainViewModel(root, prefs); Assert.Equal(78, restored.SelectedTool.PressurePercent); Assert.Equal("notein", restored.SelectedTool.PenStyleId);
        }
        finally { window?.Close(); Assert.StartsWith(Path.GetFullPath(Path.Combine(Path.GetTempPath(), ".agents", "mojian-pen-styles")) + Path.DirectorySeparatorChar, Path.GetFullPath(root)); Directory.Delete(root, true); }
    });

    [Fact]
    public void NewInkCapturesResolvedStyleAndOutlineBeforeLaterConfigurationChanges() => WpfTestExecutor.Run(() =>
    {
        var shelf = new ToolShelf(); var json = Config(); json["style"]!["endEnabled"] = true; shelf.ApplyPenStyle(shelf.Selected, shelf.ImportPenConfiguration(json.ToJsonString()));
        var page = new PageDocument("n", "p"); var canvas = new NoteCanvas { Document = page, SelectedTool = shelf.Selected };
        void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        Call("BeginPointer", new Point(20, 40), .5f, 0f, 0f, true, false, false); Call("ContinuePointer", new Point(60, 40), .5f, 0f, 0f, true); Call("EndPointer", true);
        var ink = Assert.Single(page.Strokes); Assert.Equal(PenStyle.Profile, ink.BrushProfile); Assert.NotEmpty(ink.VectorPath);
        json["style"]!["endEnabled"] = false; shelf.ImportPenConfiguration(json.ToJsonString()); Assert.True(ink.PenStyle!.EndEnabled);
        var restored = JsonSerializer.Deserialize<StrokeRecord>(JsonSerializer.Serialize(ink))!; Assert.Equal(ink.VectorPath, restored.VectorPath); Assert.True(restored.PenStyle!.EndEnabled);
    });

    private static StrokeRecord Line(PenStyle style) => new()
    {
        BrushProfile = PenStyle.Profile, PenStyle = style, WidthMm = 1, StrokeUnitLengthCm = .01,
        Points = Enumerable.Range(0, 201).Select(i => new InkPoint(i, 30, .5f, elapsedMilliseconds: i * 4)).ToList(),
    };

    [Fact]
    public void ConfiguredCurvesSwitchesTapersAndTiltChangeActualGeometry()
    {
        var style = PenStyle.FromLegacy(200, 0, 0); style.DirectionEnabled = false;
        var stroke = Line(style);
        var widths = FountainV2Model.Model(stroke);
        Assert.Equal(widths[20].Width, widths[^20].Width, 7);
        style.PressureCurve = [new(0, 2), new(1, .5)];
        var reversed = FountainV2Model.Model(stroke)[100].Width;
        Assert.True(reversed > widths[100].Width * 3);
        style.PressureEnabled = false;
        Assert.Equal(96 / 25.4, FountainV2Model.Model(stroke)[100].Width, 7);
        style.StartEnabled = style.EndEnabled = true; style.StartMinimum = style.EndMinimum = .1;
        var tapered = FountainV2Model.Model(stroke);
        Assert.True(tapered[0].Width < tapered[100].Width * .2);
        Assert.True(tapered[^1].Width < tapered[100].Width * .2);
        style.StartEnabled = style.EndEnabled = false; style.TiltEnabled = true; style.TiltWidth = 2;
        foreach (var p in stroke.Points) p.TiltX = 60;
        Assert.Equal(2 * 96 / 25.4, FountainV2Model.Model(stroke)[100].Width, 6);
        foreach (var p in stroke.Points) { p.TiltRadians = (float)(Math.PI / 6); p.OrientationRadians = .75f; }
        Assert.Equal(1.5 * 96 / 25.4, FountainV2Model.Model(stroke)[100].Width, 6);
        Assert.Equal(stroke.Points[0].TiltRadians, stroke.CopyForSave().Points[0].TiltRadians);
        style.SpeedEnabled = true; style.SpeedCurve = [new(0, .5), new(64, 2)]; style.SpeedResponseMs = 0;
        var slow = FountainV2Model.Model(stroke)[100].Width;
        stroke.StrokeUnitLengthCm = .1;
        Assert.True(FountainV2Model.Model(stroke)[100].Width > slow);
        FountainV2Model.StoreOutline(stroke);
        var restored = JsonSerializer.Deserialize<StrokeRecord>(JsonSerializer.Serialize(stroke))!;
        Assert.Equal(stroke.VectorPath, restored.VectorPath); Assert.Equal(2, restored.PenStyle!.TiltWidth);
        Assert.Equal(.75f, restored.Points[0].OrientationRadians);
    }

    private static void Capture(FrameworkElement element, string variable)
    {
        if (Environment.GetEnvironmentVariable(variable) is not { Length: > 0 } path) return;
        element.UpdateLayout(); var bitmap = new RenderTargetBitmap((int)Math.Ceiling(element.ActualWidth), (int)Math.Ceiling(element.ActualHeight), 96, 96, PixelFormats.Pbgra32); bitmap.Render(element);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); using var file = File.Create(path); encoder.Save(file);
    }
    private static void LoadTheme()
    { foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" }) Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative))); }
}
