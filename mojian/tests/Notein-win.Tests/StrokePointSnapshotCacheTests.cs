using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Tests;

public sealed class StrokePointSnapshotCacheTests
{
    [Fact]
    public void QueueSnapshotsReuseFrozenPointsWithoutSharingLiveEditsOrStyle()
    {
        var cache = new StrokePointSnapshotCache();
        var stroke = new StrokeRecord { Points = [new InkPoint(20, 30), new InkPoint(40, 50)] };
        var document = new PageDocument("n", "p") { Strokes = [stroke] };
        var first = document.CopyForSave(cache).Strokes[0];
        stroke.Color = InkPalette.Red;
        var second = document.CopyForSave(cache).Strokes[0];
        Assert.Same(first.Points, second.Points);
        Assert.NotSame(stroke.Points, first.Points);
        Assert.Equal(InkPalette.Black, first.Color);
        Assert.Equal(InkPalette.Red, second.Color);
        stroke.Points[0].X = 90;
        stroke.InvalidateBounds();
        var third = document.CopyForSave(cache).Strokes[0];
        Assert.NotSame(first.Points, third.Points);
        Assert.Equal(20, first.Points[0].X);
        Assert.Equal(90, third.Points[0].X);
        stroke.Points = [new InkPoint(100, 100), new InkPoint(200, 200)];
        Assert.Equal(100, document.CopyForSave(cache).Strokes[0].Points[0].X);
        var ordinary = document.CopyForSave();
        ordinary.Strokes[0].Points[0].X = -1;
        Assert.Equal(100, document.CopyForSave(cache).Strokes[0].Points[0].X);
    }
}
