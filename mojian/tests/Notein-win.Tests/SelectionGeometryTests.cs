using System.Windows;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class SelectionGeometryTests
{
    [Fact]
    public void PointInPolygon_CorrectlyIdentifiesPoints()
    {
        var polygon = new List<Point>
        {
            new(0, 0),
            new(100, 0),
            new(100, 100),
            new(0, 100)
        };

        Assert.True(SelectionGeometry.PointInPolygon(50, 50, polygon));
        Assert.False(SelectionGeometry.PointInPolygon(150, 50, polygon));
        Assert.False(SelectionGeometry.PointInPolygon(-10, 50, polygon));
    }

    [Fact]
    public void SquaredDistance_CalculatesEuclideanDistanceSquared()
    {
        double d2 = SelectionGeometry.SquaredDistance(0, 0, 3, 4);
        Assert.Equal(25.0, d2);
    }

    [Fact]
    public void TransformCalculations_TranslateAndScaleAccurately()
    {
        var translated = SelectionGeometry.Translate(10, 20, 5, -5);
        Assert.Equal(15.0, translated.X);
        Assert.Equal(15.0, translated.Y);

        var scaled = SelectionGeometry.Scale(20, 20, 10, 10, 2.0);
        Assert.Equal(30.0, scaled.X);
        Assert.Equal(30.0, scaled.Y);
    }

    [Fact]
    public void PolygonIntersectsRect_SelectsPartialTextOrImageOverlap()
    {
        var polygon = new List<Point> { new(0, 0), new(40, 0), new(40, 40), new(0, 40) };

        Assert.True(SelectionGeometry.PolygonIntersectsRect(polygon, new Rect(30, 30, 40, 40)));
        Assert.False(SelectionGeometry.PolygonIntersectsRect(polygon, new Rect(60, 60, 20, 20)));
    }

    [Fact]
    public void PolygonContainsRect_RequiresCompleteContainment()
    {
        var polygon = new List<Point> { new(0, 0), new(100, 0), new(100, 100), new(0, 100) };

        Assert.True(SelectionGeometry.PolygonContainsRect(polygon, new Rect(20, 20, 60, 60)));
        Assert.True(SelectionGeometry.PolygonContainsRect(polygon, new Rect(0, 0, 100, 100)));
        Assert.False(SelectionGeometry.PolygonContainsRect(polygon, new Rect(80, 80, 50, 50)));
    }

    [Fact]
    public void SelectionHandles_ExposeEightAndroidCompatiblePositions()
    {
        var handles = SelectionGeometry.SelectionHandles(new Rect(10, 20, 80, 40));

        Assert.Equal(8, handles.Count);
        Assert.Equal(new Point(50, 20), handles[1]);
        Assert.Equal(new Point(90, 40), handles[3]);
        Assert.Equal(new Point(10, 40), handles[7]);
    }

    [Fact]
    public void ResizeBounds_EdgeHandleChangesOnlyOneAxisAndHonorsMinimum()
    {
        var resized = SelectionGeometry.ResizeBounds(new Rect(10, 20, 80, 40), 3, new Point(140, 999), 24);
        Assert.Equal(new Rect(10, 20, 130, 40), resized);

        var clamped = SelectionGeometry.ResizeBounds(new Rect(10, 20, 80, 40), 7, new Point(85, 0), 24);
        Assert.Equal(66, clamped.Left);
        Assert.Equal(24, clamped.Width);
    }

    [Fact]
    public void EmptyOrWhitespaceTextDoesNotOwnASelectionFrame()
    {
        Assert.False(SelectionGeometry.HasVisibleText(null));
        Assert.False(SelectionGeometry.HasVisibleText("  \r\n"));
        Assert.True(SelectionGeometry.HasVisibleText("字"));
    }

    [Fact]
    public void TinyAndNarrowSelectionsRetainABodyTargetAtEveryZoom()
    {
        foreach (var scale in new[] { .5, 1, 8, 64 })
        foreach (var size in new[] { new Size(8, 6), new Size(4, 100), new Size(100, 4) })
        foreach (var radius in new[] { 12d, 24d })
        {
            var bounds = new Rect(100, 200, size.Width * scale, size.Height * scale);
            foreach (var fraction in new[] { .3, .5, .7 })
                Assert.Equal(-1, SelectionGeometry.HitSelectionHandle(new Point(bounds.Left + bounds.Width * fraction, bounds.Top + bounds.Height * fraction), bounds, radius));
            var handles = SelectionGeometry.SelectionHandles(bounds);
            for (var index = 0; index < handles.Count; index++)
                Assert.Equal(index, SelectionGeometry.HitSelectionHandle(handles[index], bounds, radius));
            Assert.Equal(-1, SelectionGeometry.HitSelectionHandle(new Point(bounds.Right + radius + 1, bounds.Bottom + radius + 1), bounds, radius));
        }
    }

    [Fact]
    public void AllCornersPreserveRatioAndOppositeAnchorIncludingMinimumClamp()
    {
        var start = new Rect(10, 20, 80, 40);
        foreach (var handle in new[] { 0, 2, 4, 6 })
        {
            var points = SelectionGeometry.SelectionHandles(start);
            var corner = points[handle];
            var anchor = points[(handle + 4) % 8];
            foreach (var target in new[] { corner + new Vector(91, -27), anchor, anchor + (anchor - corner) })
            {
                var resized = SelectionGeometry.ResizeBounds(start, handle, target, 24);
                Assert.Equal(2, resized.Width / resized.Height, 8);
                Assert.Equal(anchor, SelectionGeometry.SelectionHandles(resized)[(handle + 4) % 8]);
                Assert.True(resized.Height >= 24);
            }
        }
        var tiny = new Rect(10, 20, 8, 4);
        Assert.Equal(tiny, SelectionGeometry.ResizeBounds(tiny, 4, tiny.BottomRight, 24));
    }

    [Fact]
    public void AllEdgeHandlesChangeOnlyTheirOwnAxis()
    {
        var start = new Rect(10, 20, 80, 40);
        foreach (var handle in new[] { 1, 3, 5, 7 })
        {
            var resized = SelectionGeometry.ResizeBounds(start, handle, new Point(200, 200), 24);
            if (handle is 1 or 5) { Assert.Equal(start.Left, resized.Left); Assert.Equal(start.Width, resized.Width); }
            else { Assert.Equal(start.Top, resized.Top); Assert.Equal(start.Height, resized.Height); }
        }
    }
}
