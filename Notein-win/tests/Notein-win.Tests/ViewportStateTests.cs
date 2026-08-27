using Notein_win.Services;
using Notein_win.Models;
using System.Windows;
using Xunit;

namespace Notein_win.Tests;

public class ViewportStateTests
{
    [Fact]
    public void CoordinateConversion_IsReversible()
    {
        var viewport = new ViewportState();
        viewport.Pan(150, 200);
        viewport.ZoomAt(300, 300, 2.0f);

        double docX = 42.5;
        double docY = 98.2;

        var viewPt = viewport.DocumentToView(docX, docY);
        var restoredDocPt = viewport.ViewToDocument(viewPt.X, viewPt.Y);

        Assert.Equal(docX, restoredDocPt.X, precision: 3);
        Assert.Equal(docY, restoredDocPt.Y, precision: 3);
    }

    [Fact]
    public void ZoomAt_ClampsToMinAndMaxScale()
    {
        var viewport = new ViewportState(minScale: 0.5f, maxScale: 5.0f);

        viewport.ZoomAt(100, 100, 10.0f);
        Assert.Equal(5.0f, viewport.Scale);

        viewport.ZoomAt(100, 100, 0.01f);
        Assert.Equal(0.5f, viewport.Scale);
    }

    [Fact]
    public void RadialDragZoom_UsesCanvasCenterInsteadOfScreenAxis()
    {
        var center = new Point(500, 400);
        var previous = new Point(650, 400);

        Assert.True(ViewportState.RadialDragZoomFactor(center, previous, new Point(600, 400)) < 1);
        Assert.True(ViewportState.RadialDragZoomFactor(center, previous, new Point(700, 400)) > 1);
        Assert.Equal(1f, ViewportState.RadialDragZoomFactor(center, previous, new Point(500, 550)), precision: 5);
    }

    [Fact]
    public void RadialDragZoom_AwayFromCenterIncreasesViewportScale()
    {
        var viewport = new ViewportState();
        var center = new Point(500, 400);
        var start = new Point(600, 500);
        var outward = new Point(700, 600);

        viewport.ZoomAt((float)start.X, (float)start.Y, ViewportState.RadialDragZoomFactor(center, start, outward));
        Assert.True(viewport.Scale > 1f);

        viewport.Reset();
        viewport.ZoomAt((float)start.X, (float)start.Y, ViewportState.RadialDragZoomFactor(center, start, new Point(550, 450)));
        Assert.True(viewport.Scale < 1f);
    }

    [Theory]
    [InlineData(true, true, DrawingToolKind.Pen, CanvasPointerCursorMode.Hand)]
    [InlineData(true, false, DrawingToolKind.Pen, CanvasPointerCursorMode.Arrow)]
    [InlineData(false, true, DrawingToolKind.Pen, CanvasPointerCursorMode.PenDot)]
    [InlineData(false, false, DrawingToolKind.Pen, CanvasPointerCursorMode.Arrow)]
    public void PointerFeedback_ReflectsPanoramaAndPhysicalStylus(
        bool penHand,
        bool stylusInRange,
        DrawingToolKind tool,
        CanvasPointerCursorMode expected)
    {
        Assert.Equal(expected, CanvasPointerFeedback.Resolve(penHand, stylusInRange, tool));
    }

    [Fact]
    public void StylusViewportGesturesUseOneStreamAndOnlyTheLatestCoalescedSample()
    {
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(true, false, false));
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(false, true, false));
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(false, false, true));
        Assert.False(CanvasPointerFeedback.SuppressMouseStream(false, false, false));

        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(true, false, false));
        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(false, true, false));
        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(false, false, true));
        Assert.False(CanvasPointerFeedback.UseLatestStylusSample(false, false, false));
    }
}
