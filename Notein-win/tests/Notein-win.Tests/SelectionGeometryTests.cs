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
}
