using System.IO;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
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
    [Fact]
    public void SharedBuiltinsAndLegacySettingsHaveEquivalentCalibratedCurves()
    {
        var natural = NamedPenStyle.Defaults().Single(p => p.Id == "natural").Style;
        Assert.Equal("natural", new ToolShelf().Selected.PenStyleId);
        Assert.Equal(3, new ToolShelf().PenStyles.Count);
        Assert.All(NamedPenStyle.Defaults(), p => Assert.NotNull(PenStyle.FromJson(p.Style.ToJson())));
        for (var p = 0; p <= 16; p++)
        foreach (var v in new double[] { 0, 1, 2, 4, 8, 16, 32, 64 })
            Assert.Equal(FountainV2Model.WidthMultiplier(p / 16d, 200, v, 100, 0),
                PenStyle.Evaluate(natural.PressureCurve, p / 16d) * PenStyle.Evaluate(natural.SpeedCurve, v), 7);
        Assert.False(natural.StartEnabled); Assert.False(natural.EndEnabled);
        Assert.True(NamedPenStyle.Defaults().Single(p => p.Id == "classic").Style.TurnEnabled);
    }

    [Fact]
    public void SaveNewEditExistingAndRestorePreserveReferencesAndHistoricalSnapshots()
    {
        var shelf = new ToolShelf();
        var black = shelf.Tools.Single(t => t.Id == "pen-black");
        var blue = shelf.Tools.Single(t => t.Id == "pen-blue");
        var draft = PenStyle.FromLegacy(78, 20, 0);
        var saved = shelf.SavePenStyle(null, "硬笔练字", draft);
        shelf.ApplyPenStyle(black, saved); shelf.ApplyPenStyle(blue, saved);
        var historical = new StrokeRecord { PenStyle = black.PenStyle!.Copy(), BrushProfile = PenStyle.Profile };
        draft.PressureCurve[0] = new(0, 2);
        Assert.Equal(0, saved.Style.PressureCurve[0].Y);
        var changed = saved.Style.Copy(); changed.EndEnabled = true; changed.StabilityPercent = 75;
        shelf.SavePenStyle(saved.Id, "硬笔练字 2", changed);
        Assert.True(black.PenStyle!.EndEnabled); Assert.True(blue.PenStyle!.EndEnabled);
        Assert.Equal(75, black.StabilityPercent); Assert.False(historical.PenStyle!.EndEnabled);
        Assert.Equal(.4f, black.WidthMm); Assert.Equal(InkPalette.Blue, blue.Color);
        var restored = ToolShelf.FromJson(shelf.ToJson())!;
        Assert.Equal("硬笔练字 2", restored.PenStyles.Single(p => p.Id == saved.Id).Name);
        Assert.Equal(saved.Id, restored.Tools.Single(t => t.Id == "pen-black").PenStyleId);
        Assert.True(restored.Tools.Single(t => t.Id == "pen-blue").PenStyle!.EndEnabled);
        Assert.Throws<ArgumentException>(() => shelf.SavePenStyle(null, "硬笔练字 2", changed));
        Assert.Throws<ArgumentException>(() => shelf.SavePenStyle(null, " ", changed));
        var natural = shelf.PenStyles.Single(p => p.Id == "natural").Style.Copy(); natural.PressureEnabled = false;
        shelf.SavePenStyle("natural", "自然钢笔", natural);
        Assert.False(shelf.Add(DrawingToolKind.Pen).PenStyle!.PressureEnabled);
        var snapshot = historical.CopyForSave();
        historical.PenStyle.PressureCurve[0] = new(0, 1);
        Assert.Equal(0, snapshot.PenStyle!.PressureCurve[0].Y);
    }

    [Fact]
    public void LegacyToolsKeepTheirParametersAndRendererUntilAStyleIsChosen()
    {
        var tool = new ToolPreset("pen-black", DrawingToolKind.Pen) { PressurePercent = 78, InkFlowPercent = 20, StabilityPercent = 0 };
        var oldJson = "{\"selectedId\":\"pen-black\",\"tools\":[" + tool.ToJson().ToJsonString() + "]}";
        var shelf = ToolShelf.FromJson(oldJson)!;
        Assert.Null(shelf.Selected.PenStyle);
        Assert.Equal(78, shelf.Selected.PressurePercent); Assert.Equal(20, shelf.Selected.InkFlowPercent);
        Assert.Contains(shelf.PenStyles, p => p.Id == shelf.Selected.PenStyleId);
        Assert.Equal("fountain-v1", new StrokeRecord().BrushProfile);
        Assert.Null(PenStyle.FromJson(PenStyle.FromLegacy().ToJson().ApplyInvalidCurve()));
    }

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

    [Fact]
    public void NativeAuthoringCapturesStyleBeforeLaterPresetEdits() => WpfTestExecutor.Run(() =>
    {
        var shelf = new ToolShelf(); var tool = shelf.Selected;
        shelf.ApplyPenStyle(tool, shelf.PenStyles.Single(p => p.Id == "classic"));
        var page = new PageDocument("n", "p"); var canvas = new NoteCanvas { Document = page, SelectedTool = tool };
        void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        Call("BeginPointer", new Point(20, 40), .5f, 0f, 0f, true, false, false);
        Call("ContinuePointer", new Point(60, 40), .5f, 0f, 0f, true);
        Call("EndPointer", true);
        var ink = Assert.Single(page.Strokes);
        Assert.Equal(PenStyle.Profile, ink.BrushProfile); Assert.NotEmpty(ink.VectorPath);
        tool.PenStyle!.EndEnabled = false;
        Assert.True(ink.PenStyle!.EndEnabled);
        var cloned = (StrokeRecord)typeof(NoteCanvas).GetMethod("CloneStroke", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [ink, 10f])!;
        Assert.Equal(ink.VectorPath, cloned.VectorPath); Assert.True(cloned.PenStyle!.EndEnabled);
    });

    [Fact]
    public void ConfigurationDraftCancelIsIsolatedAndNativeLayoutRenders() => WpfTestExecutor.Run(() =>
    {
        LoadTheme();
        var shelf = new ToolShelf(); var before = shelf.ToJson();
        var dialog = new PenStyleDialog(shelf, shelf.Selected);
        dialog.Show(); dialog.UpdateLayout();
        var draft = (PenStyle)typeof(PenStyleDialog).GetField("_draft", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(dialog)!;
        draft.EndEnabled = true; draft.PressureCurve[0] = new(0, 2);
        Assert.Equal(before, shelf.ToJson());
        var path = Environment.GetEnvironmentVariable("MOJIAN_PEN_STYLE_SCREENSHOT");
        if (!string.IsNullOrEmpty(path))
        {
            var bitmap = new RenderTargetBitmap((int)dialog.ActualWidth, (int)dialog.ActualHeight, 96, 96, PixelFormats.Pbgra32); bitmap.Render(dialog);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var file = File.Create(path); encoder.Save(file);
        }
        dialog.Close();
        var saveDialog = new PenStyleDialog(shelf, shelf.Selected);
        saveDialog.Dispatcher.BeginInvoke(() =>
        {
            saveDialog.NewPreset();
            ((TextBox)typeof(PenStyleDialog).GetField("_name", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(saveDialog)!).Text = "课堂笔";
            saveDialog.Save();
        }, DispatcherPriority.Loaded);
        Assert.True(saveDialog.ShowDialog());
        Assert.Equal("课堂笔", shelf.PenStyles.Single(p => p.Id == shelf.Selected.PenStyleId).Name);
        var count = shelf.PenStyles.Count; var id = shelf.Selected.PenStyleId;
        var editDialog = new PenStyleDialog(shelf, shelf.Selected);
        editDialog.Dispatcher.BeginInvoke(() =>
        {
            ((PenStyle)typeof(PenStyleDialog).GetField("_draft", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(editDialog)!).EndEnabled = true;
            editDialog.Save();
        }, DispatcherPriority.Loaded);
        Assert.True(editDialog.ShowDialog()); Assert.Equal(count, shelf.PenStyles.Count); Assert.Equal(id, shelf.Selected.PenStyleId);
        Assert.True(shelf.Selected.PenStyle!.EndEnabled);
    });

    [Fact]
    public void ToolFlyoutHasIndependentSavedPresetSelectorAndConfigurationButton() => WpfTestExecutor.Run(() =>
    {
        LoadTheme();
        var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-pen-styles", "fixture-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        MainWindow? window = null;
        try
        {
            var prefs = new PreferencesStore(Path.Combine(root, "preferences.json"));
            var model = new MainViewModel(root, prefs);
            model.AddPage("preset fixture", new PageAppearance()); model.SaveAll();
            window = new MainWindow(model); window.Show();
            window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
            var selector = (ComboBox)window.FindName("PenStyleSelector");
            Assert.Equal(model.ToolShelf.PenStyles.Count, selector.Items.Count);
            Assert.All(selector.Items.Cast<object>(), p => Assert.IsType<NamedPenStyle>(p));
            var file = model.Store.GetPageDocumentPath(model.PrimaryDocument!.NotebookId, model.PrimaryDocument.PageId);
            var savedInk = File.ReadAllBytes(file);
            selector.SelectedValue = "uniform";
            Assert.Equal("uniform", model.SelectedTool.PenStyleId);
            Assert.False(model.SelectedTool.PenStyle!.PressureEnabled);
            Assert.Equal(savedInk, File.ReadAllBytes(file));
            var popup = (System.Windows.Controls.Primitives.Popup)window.FindName("ToolFlyoutPopup");
            popup.PlacementTarget = window; popup.PlacementRectangle = new Rect(20, 80, 1, 1); model.IsToolFlyoutOpen = true;
            window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
            var child = (FrameworkElement)popup.Child;
            IEnumerable<DependencyObject> Descendants(DependencyObject node)
            {
                yield return node;
                for (var i = 0; i < VisualTreeHelper.GetChildrenCount(node); i++)
                    foreach (var nested in Descendants(VisualTreeHelper.GetChild(node, i))) yield return nested;
            }
            Assert.Single(Descendants(child).OfType<Button>(), b => Equals(b.Content, "配置"));
            Assert.True(child.ActualHeight >= 180);
            var path = Environment.GetEnvironmentVariable("MOJIAN_PEN_TOOL_SCREENSHOT");
            if (!string.IsNullOrEmpty(path))
            {
                var bitmap = new RenderTargetBitmap((int)Math.Ceiling(child.ActualWidth), (int)Math.Ceiling(child.ActualHeight), 96, 96, PixelFormats.Pbgra32); bitmap.Render(child);
                var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); using var output = File.Create(path); encoder.Save(output);
            }
            window.Close(); window = null;
            var restored = new MainViewModel(root, prefs);
            Assert.Equal("uniform", restored.SelectedTool.PenStyleId); Assert.False(restored.SelectedTool.PenStyle!.PressureEnabled);
        }
        finally
        {
            window?.Close();
            Assert.StartsWith(Path.GetFullPath(Path.Combine(Path.GetTempPath(), ".agents", "mojian-pen-styles")) + Path.DirectorySeparatorChar, Path.GetFullPath(root));
            Directory.Delete(root, true);
        }
    });

    private static void LoadTheme()
    {
        foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
            Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
    }
}

internal static class InvalidPenStyleFixture
{
    public static System.Text.Json.Nodes.JsonObject ApplyInvalidCurve(this System.Text.Json.Nodes.JsonObject value)
    {
        value["pressureCurve"]![1]!["x"] = 0; return value;
    }
}
