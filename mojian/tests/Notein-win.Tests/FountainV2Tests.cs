using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public sealed class FountainV2Tests
{
    [Theory]
    [InlineData(0, 1)] [InlineData(25, 10)] [InlineData(50, 20)]
    [InlineData(75, 60)] [InlineData(100, 100)] [InlineData(-10, 1)] [InlineData(200, 100)]
    public void StabilityMatchesAndroid(int percent, int window) => Assert.Equal(window, FountainV2Model.StabilityWindowMillis(percent));

    [Theory]
    [InlineData(1, 0)] [InlineData(2, .08)] [InlineData(4, .15)] [InlineData(8, .23)]
    [InlineData(16, .46)] [InlineData(32, .69)] [InlineData(64, .85)] [InlineData(200, .85)]
    public void CalibratedSpeedTable(double speed, double thinning) => Assert.Equal(thinning, FountainV2Model.SpeedThinning(speed), 8);

    [Fact]
    public void PressureDirectionAndExtendedFlowMatchAndroid()
    {
        Assert.Equal(1.4 * .25, FountainV2Model.WidthMultiplier(.5, 200, 0, 100, 0), 8);
        Assert.Equal(0, FountainV2Model.WidthMultiplier(0, 200, 0, 100, 0), 8);
        Assert.Equal(1.4, FountainV2Model.WidthMultiplier(.01, 0, 0, 0, 0), 8);
        Assert.Equal(1.4 * 1.17, FountainV2Model.WidthMultiplier(1, 200, 0, 0, 1), 8);
        Assert.Equal(1.4 * .15, FountainV2Model.WidthMultiplier(1, 200, 64, 100, 0), 8);
        Assert.Equal(1.4 * (1 - .95 * .46 / .85), FountainV2Model.WidthMultiplier(1, 200, 16, 200, 0), 8);
    }

    [Fact]
    public void PacketTimesUseEventIntervalsAndSurviveWrapDuplicatesAndDelayedPackets()
    {
        var clock = new StylusPacketClock();
        clock.Reset(int.MaxValue - 7);
        Assert.Equal(new long[] { 4, 8, 12, 16 }, clock.Distribute(unchecked(int.MinValue + 8), 4));
        Assert.Equal(new long[] { 17, 18, 19 }, clock.Distribute(unchecked(int.MinValue + 8), 3));
        var next = clock.Distribute(unchecked(int.MinValue + 24), 4);
        Assert.Equal(32, next[^1]);
        Assert.True(next.Zip(next.Skip(1), (a, b) => a < b).All(x => x));
        Assert.Empty(clock.Distribute(0, 0));
    }

    [Fact]
    public void PhysicalSpeedIncludesZoomPixelsAndRawPanelDpi()
    {
        Assert.Equal(2.54 / 96, InkPhysicalScale.CentimetersPerUnit(1, 1, 96), 10);
        Assert.Equal(2.54 / 96, InkPhysicalScale.CentimetersPerUnit(1, 2, 192), 10);
        Assert.Equal(4 * 2.54 / 96, InkPhysicalScale.CentimetersPerUnit(4, 2, 192), 10);
    }

    private static StrokeRecord Stroke(int stability = 50, double unitCm = .02) => new()
    {
        BrushProfile = FountainV2Model.Profile, WidthMm = 1, StabilityPercent = stability, StrokeUnitLengthCm = unitCm,
        Points = Enumerable.Range(0, 101).Select(i => new InkPoint(i * 2, 50, i < 50 ? .25f : .8f, elapsedMilliseconds: i * 2)).ToList(),
    };

    [Fact]
    public void SmoothingIsBasedOnTimeAndDoesNotAddDistanceTapers()
    {
        var fast = FountainV2Model.Model(Stroke(0));
        var stable = FountainV2Model.Model(Stroke(100));
        Assert.True(fast[60].Pressure > stable[60].Pressure + .1);
        Assert.Equal(fast[^1].Position, stable[^1].Position);
        Assert.Equal(fast[0].Position, stable[0].Position);
        var corner = Stroke(0);
        for (var i = 0; i < corner.Points.Count; i++) corner.Points[i].Y = Math.Abs(i - 50) * 2;
        var sharp = FountainV2Model.Model(corner);
        corner.StabilityPercent = 100;
        Assert.True(FountainV2Model.Model(corner)[50].Position.Y > sharp[50].Position.Y + 15);
        var constant = Stroke(0);
        constant.InkFlowPercent = 0;
        foreach (var point in constant.Points) point.Pressure = .5f;
        var widths = FountainV2Model.Model(constant).Select(s => s.Width).ToArray();
        Assert.All(widths, w => Assert.Equal(widths[0], w, 7));
        var fasterPhysical = FountainV2Model.Model(Stroke(0, .04));
        Assert.True(fasterPhysical[^1].Width < fast[^1].Width);
    }

    [Fact]
    public void ToolStabilityRoundTripsAndLegacyDefaultsToFifty()
    {
        var tool = new ToolPreset("pen", DrawingToolKind.Pen) { StabilityPercent = 75 };
        Assert.Equal(75, ToolPreset.FromJson(tool.ToJson())!.StabilityPercent);
        var json = tool.ToJson();
        json.Remove("stabilityPercent");
        Assert.Equal(50, ToolPreset.FromJson(json)!.StabilityPercent);
        json["stabilityPercent"] = 200;
        Assert.Equal(100, ToolPreset.FromJson(json)!.StabilityPercent);
        Assert.Equal("fountain-v1", new StrokeRecord().BrushProfile);
    }

    [Fact]
    public void OutlineRoundTripTransformsCopiesAndPixelsStayExact() => WpfTestExecutor.Run(() =>
    {
        var stroke = Stroke(75);
        var before = FountainV2Model.CreateGeometry(stroke);
        FountainV2Model.StoreOutline(stroke);
        Assert.StartsWith("F1", stroke.VectorPath);
        Assert.DoesNotContain("A", stroke.VectorPath); // iOS shared parser supports C, not SVG arcs.
        var restored = JsonSerializer.Deserialize<StrokeRecord>(JsonSerializer.Serialize(stroke))!;
        Assert.Equal(75, restored.StabilityPercent);
        Assert.Equal(.02, restored.StrokeUnitLengthCm);
        Assert.Equal(stroke.VectorPath, restored.VectorPath);
        byte[] Pixels(Geometry geometry)
        {
            var visual = new DrawingVisual();
            using (var dc = visual.RenderOpen()) dc.DrawGeometry(Brushes.Black, null, geometry);
            var bitmap = new RenderTargetBitmap(240, 100, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var pixels = new byte[240 * 100 * 4]; bitmap.CopyPixels(pixels, 240 * 4, 0);
            return pixels;
        }
        Assert.Equal(Pixels(before), Pixels(restored.VectorGeometry!));
        var bounds = restored.Bounds;
        restored.TransformVector(new Matrix(2, 0, 0, 3, 10, 20));
        Assert.Equal(bounds.Left * 2 + 10, restored.Bounds.Left, 6);
        Assert.Equal(bounds.Width * 2, restored.Bounds.Width, 6);
        var snapshot = restored.CopyForSave();
        restored.Points[0].X = -900;
        restored.TransformVector(new Matrix(1, 0, 0, 1, 100, 0));
        Assert.Equal(stroke.Points[0].X, snapshot.Points[0].X);
        Assert.Equal(stroke.VectorPath, snapshot.VectorPath);
        Assert.NotEqual(restored.VectorTransform, snapshot.VectorTransform);
    });

    [Fact]
    public void NativeAuthoringStoresOutlineAndPreservesItThroughCopyRecolorEraseAndUndo() => WpfTestExecutor.Run(() =>
    {
        var document = new PageDocument("n", "p");
        var canvas = new NoteCanvas { Document = document, SelectedTool = new ToolPreset("pen", DrawingToolKind.Pen) { StabilityPercent = 75 } };
        void Call(string name, params object[] args) => typeof(NoteCanvas).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(canvas, args);
        Call("BeginPointer", new Point(20, 40), .8f, 0f, 0f, true, false, false);
        Call("ContinuePointer", new Point(60, 40), .5f, 0f, 0f, true);
        Call("ContinuePointer", new Point(100, 40), .2f, 0f, 0f, true);
        Call("EndPointer", true);
        var stroke = Assert.Single(document.Strokes);
        Assert.Equal(FountainV2Model.Profile, stroke.BrushProfile);
        Assert.Equal(75, stroke.StabilityPercent);
        Assert.Equal(100, stroke.OpacityPercent);
        Assert.NotEmpty(stroke.VectorPath);
        Assert.True(stroke.Points.Zip(stroke.Points.Skip(1), (a, b) => a.ElapsedMilliseconds < b.ElapsedMilliseconds).All(x => x));
        Call("SelectOnlyStroke", stroke);
        Call("TranslateSelection", 15d, 20d);
        var moved = stroke.Bounds;
        var clone = (StrokeRecord)typeof(NoteCanvas).GetMethod("CloneStroke", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, [stroke, 30f])!;
        Assert.Equal(stroke.VectorPath, clone.VectorPath);
        Assert.Equal(75, clone.StabilityPercent);
        Assert.Equal(moved.Left + 30, clone.Bounds.Left, 6);
        stroke.Color = InkPalette.Red;
        var center = new Point(moved.Left + moved.Width / 2, moved.Top + moved.Height / 2);
        canvas.SelectedTool = new ToolPreset("erase", DrawingToolKind.Eraser);
        Call("BeginPointer", center, .5f, 0f, 0f, true, false, false);
        Call("EndPointer", true);
        Assert.Empty(document.Strokes);
        canvas.Undo();
        Assert.NotEmpty(document.Strokes);
    });
}
