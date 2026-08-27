using Notein_win.Models;
using Notein_win.Services;
using Xunit;

namespace Notein_win.Tests;

public class HandwritingEngineTests
{
    [Fact]
    public void PressureCorrection_MakesHighPressureWider()
    {
        var low = Stroke(0.2f, 0.2f);
        var high = Stroke(0.9f, 0.9f);
        Assert.True(HandwritingEngine.SegmentWidth(high, 1) > HandwritingEngine.SegmentWidth(low, 1));
    }

    [Fact]
    public void Prediction_UsesRecentVelocityAndClampsDistance()
    {
        var points = new List<InkPoint>
        {
            new(0, 0, elapsedMilliseconds: 0),
            new(5, 0, elapsedMilliseconds: 5),
            new(15, 0, elapsedMilliseconds: 10),
        };
        var predicted = HandwritingEngine.Predict(points, horizonMilliseconds: 20, maxDistance: 12);
        Assert.NotNull(predicted);
        Assert.InRange(predicted!.Value.X, 26.9, 27.1);
        Assert.Equal(0, predicted.Value.Y, 3);
    }

    [Fact]
    public void Smoothing_PreservesEndpoints()
    {
        var points = new List<InkPoint> { new(0, 0), new(10, 20), new(20, 0) };
        Assert.Equal(points[0].ToPoint(), HandwritingEngine.SmoothedPoint(points, 0));
        Assert.Equal(points[2].ToPoint(), HandwritingEngine.SmoothedPoint(points, 2));
        Assert.Equal(12, HandwritingEngine.SmoothedPoint(points, 1).Y, 3);
    }

    [Fact]
    public void PressureEraserCanArmAfterPressureRisesDuringContactAndActivatesAtDwell()
    {
        Assert.False(PressureEraserPolicy.CanStartCandidate(true, true, DrawingToolKind.Pen, true, 0.25f, 78));
        Assert.True(PressureEraserPolicy.CanStartCandidate(true, true, DrawingToolKind.Pen, true, 0.82f, 78));
        Assert.False(PressureEraserPolicy.CanStartCandidate(false, true, DrawingToolKind.Pen, true, 0.82f, 78));
        Assert.False(PressureEraserPolicy.CanStartCandidate(true, false, DrawingToolKind.Pen, true, 0.82f, 78));

        Assert.False(PressureEraserPolicy.ShouldActivate(true, true, 139, 140));
        Assert.True(PressureEraserPolicy.ShouldActivate(true, true, 140, 140));
        Assert.False(PressureEraserPolicy.ShouldActivate(true, false, 200, 140));
    }

    private static StrokeRecord Stroke(float firstPressure, float secondPressure) => new()
    {
        WidthMm = 0.4f,
        PressurePercent = 200,
        InkFlowPercent = 100,
        Points = new List<InkPoint>
        {
            new(0, 0, firstPressure, elapsedMilliseconds: 0),
            new(4, 0, secondPressure, elapsedMilliseconds: 10),
        },
    };
}
