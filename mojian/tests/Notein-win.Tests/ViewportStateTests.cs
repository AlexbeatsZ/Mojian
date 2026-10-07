using Notein_win.Services;
using Notein_win.Models;
using System.Windows;
using Xunit;

namespace Notein_win.Tests;

public class ViewportStateTests
{
    [Theory]
    [InlineData(8f)]
    [InlineData(16f)]
    [InlineData(32f)]
    [InlineData(64f)]
    public void HighZoomPresetsKeepTheAnchorAndCoordinateConversion(float scale)
    {
        var viewport = new ViewportState();
        viewport.Pan(45, 70);
        var anchor = viewport.ViewToDocument(300, 180);

        viewport.SetScale(scale, 300, 180);

        Assert.Equal(scale, viewport.Scale);
        Assert.Equal(anchor, viewport.ViewToDocument(300, 180));
        var point = viewport.DocumentToView(42.5, 98.2);
        var restored = viewport.ViewToDocument(point.X, point.Y);
        Assert.Equal(42.5, restored.X, precision: 3);
        Assert.Equal(98.2, restored.Y, precision: 3);
    }

    [Fact]
    public void GestureAtTheNewMaximumStillPansAndCanZoomBackOut()
    {
        var viewport = new ViewportState();
        viewport.ApplyGesture(100, 120, 100, 120, 100);
        Assert.Equal(64f, viewport.Scale);
        var anchor = viewport.ViewToDocument(100, 120);

        viewport.ApplyGesture(100, 120, 135, 170, 2);
        Assert.Equal(64f, viewport.Scale);
        Assert.Equal(anchor, viewport.ViewToDocument(135, 170));

        viewport.ZoomAt(135, 170, 0.5f);
        Assert.Equal(32f, viewport.Scale);
        Assert.Equal(anchor, viewport.ViewToDocument(135, 170));
        viewport.ZoomAt(135, 170, 0.001f);
        Assert.Equal(0.5f, viewport.Scale);
    }

    [Fact]
    public void PersistedHighZoomRestoresWithoutFallingBackToTheOldMaximum()
    {
        var viewport = new ViewportState();
        viewport.SetScale(64, 300, 180);
        var saved = new PageViewport();
        viewport.CopyTo(saved);
        var roundTrip = System.Text.Json.JsonSerializer.Deserialize<PageViewport>(System.Text.Json.JsonSerializer.Serialize(saved))!;
        var restored = new ViewportState();
        restored.Restore(roundTrip);

        Assert.Equal(64f, restored.Scale);
        Assert.Equal(viewport.TranslationX, restored.TranslationX);
        Assert.Equal(viewport.TranslationY, restored.TranslationY);
        restored.Restore(new PageViewport { Scale = 128 });
        Assert.Equal(64f, restored.Scale);
    }

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
    public void DiagonalDragZoom_UsesFixedScreenDirectionInsteadOfCanvasCenter()
    {
        var previous = new Point(650, 200);

        Assert.True(ViewportState.DiagonalDragZoomFactor(previous, new Point(750, 300)) > 1);
        Assert.True(ViewportState.DiagonalDragZoomFactor(previous, new Point(550, 100)) < 1);
        Assert.Equal(1f, ViewportState.DiagonalDragZoomFactor(previous, new Point(750, 100)), precision: 5);
    }

    [Fact]
    public void DiagonalDragZoom_DownRightIncreasesAndUpLeftDecreasesViewportScale()
    {
        var viewport = new ViewportState();
        var start = new Point(600, 500);
        var downRight = new Point(700, 600);

        viewport.ZoomAt((float)start.X, (float)start.Y, ViewportState.DiagonalDragZoomFactor(start, downRight));
        Assert.True(viewport.Scale > 1f);

        viewport.Reset();
        viewport.ZoomAt((float)start.X, (float)start.Y, ViewportState.DiagonalDragZoomFactor(start, new Point(550, 450)));
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
    public void StylusRemainsTheDriverForMiddlePanAndCtrlMiddleZoomWhileMouseCopiesAreIgnored()
    {
        // These true results make the physical stylus continue the active
        // viewport gesture with its newest sample. They do not disable it.
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(true, false, false));
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(false, true, false));
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(false, false, true));
        Assert.False(CanvasPointerFeedback.SuppressMouseStream(false, false, false));

        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(true, false, false));
        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(false, true, false));
        Assert.True(CanvasPointerFeedback.UseLatestStylusSample(false, false, true));
        Assert.False(CanvasPointerFeedback.UseLatestStylusSample(false, false, false));
    }

    [Fact]
    public void PromotedStylusMouseMoveStillRefreshesHoverWithoutDrivingASecondGesture()
    {
        Assert.True(CanvasPointerFeedback.RefreshHoverFromPromotedMouse(true, false));
        Assert.False(CanvasPointerFeedback.RefreshHoverFromPromotedMouse(true, true));
        Assert.False(CanvasPointerFeedback.RefreshHoverFromPromotedMouse(false, false));
        Assert.True(CanvasPointerFeedback.SuppressMouseStream(false, true, false));
    }

    [Fact]
    public void HoldingMiddleLetsInAirStylusMotionDriveNavigationWithoutASecondContact()
    {
        Assert.True(CanvasPointerFeedback.PromotedHoverDrivesMiddleNavigation(true, false, true, true));
        Assert.False(CanvasPointerFeedback.PromotedHoverDrivesMiddleNavigation(true, true, true, true));
        Assert.False(CanvasPointerFeedback.PromotedHoverDrivesMiddleNavigation(true, false, false, true));
        Assert.False(CanvasPointerFeedback.PromotedHoverDrivesMiddleNavigation(false, false, true, true));

        Assert.True(CanvasPointerFeedback.ContactStylusDrivesMiddleNavigation(true, true, true));
        Assert.False(CanvasPointerFeedback.ContactStylusDrivesMiddleNavigation(false, true, true));
    }

    [Fact]
    public void CtrlCanSwitchHeldMiddleNavigationBetweenPanAndZoomWithoutAnotherClick()
    {
        Assert.False(CanvasPointerFeedback.NavigationUsesRadialZoom(false, false));
        Assert.True(CanvasPointerFeedback.NavigationUsesRadialZoom(true, false));
        Assert.False(CanvasPointerFeedback.NavigationUsesRadialZoom(true, true));
    }

    [Fact]
    public void RightButtonDragStartsTemporaryLassoButStationaryRightClickDoesNot()
    {
        Assert.False(CanvasPointerFeedback.RightButtonMovementStartsLasso(3.9, 4));
        Assert.True(CanvasPointerFeedback.RightButtonMovementStartsLasso(4, 4));
        Assert.True(CanvasPointerFeedback.RightButtonMovementStartsLasso(12, 4));
        Assert.False(CanvasPointerFeedback.StylusBarrelHoldStartsLasso(false, 5_000, 500));
        Assert.False(CanvasPointerFeedback.StylusBarrelHoldStartsLasso(true, 499, 500));
        Assert.True(CanvasPointerFeedback.StylusBarrelHoldStartsLasso(true, 500, 500));
    }

    [Fact]
    public void DriverPromotedRightButtonUsesHoveringStylusAndNeverStartsInkBeforeMousePromotion()
    {
        Assert.True(CanvasPointerFeedback.RightButtonUsesStylus(false, true, false));
        Assert.True(CanvasPointerFeedback.RightButtonUsesStylus(false, false, true));
        Assert.False(CanvasPointerFeedback.RightButtonUsesStylus(false, false, false));

        Assert.True(CanvasPointerFeedback.RightButtonGestureIntent(
            gestureArmed: false,
            barrelButtonDownEventLatched: true,
            barrelButtonPressedInSnapshot: false));
        Assert.True(CanvasPointerFeedback.RightButtonGestureIntent(
            gestureArmed: false,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: true));
        Assert.False(CanvasPointerFeedback.RightButtonGestureIntent(
            gestureArmed: false,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: false));

        Assert.True(CanvasPointerFeedback.IgnoreStalePromotedStylusRightDown(
            promotedByStylus: true,
            gestureArmed: false,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: false));
        Assert.False(CanvasPointerFeedback.IgnoreStalePromotedStylusRightDown(
            promotedByStylus: true,
            gestureArmed: false,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: true));
        Assert.False(CanvasPointerFeedback.IgnoreStalePromotedStylusRightDown(
            promotedByStylus: true,
            gestureArmed: true,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: false));
        Assert.False(CanvasPointerFeedback.IgnoreStalePromotedStylusRightDown(
            promotedByStylus: false,
            gestureArmed: false,
            barrelButtonDownEventLatched: false,
            barrelButtonPressedInSnapshot: false));

        Assert.True(CanvasPointerFeedback.StylusUpEndsRightButtonGesture(
            gestureArmed: true,
            usesStylus: true,
            barrelButtonPressedInSnapshot: false));
        Assert.False(CanvasPointerFeedback.StylusUpEndsRightButtonGesture(
            gestureArmed: true,
            usesStylus: true,
            barrelButtonPressedInSnapshot: true));
        Assert.False(CanvasPointerFeedback.StylusUpEndsRightButtonGesture(
            gestureArmed: true,
            usesStylus: false,
            barrelButtonPressedInSnapshot: false));

        Assert.False(CanvasPointerFeedback.StylusDownStartsDrawing(inAir: true, rightButtonGestureIntent: false));
        Assert.False(CanvasPointerFeedback.StylusDownStartsDrawing(inAir: false, rightButtonGestureIntent: true));
        Assert.True(CanvasPointerFeedback.StylusDownStartsDrawing(inAir: false, rightButtonGestureIntent: false));
    }

    [Fact]
    public void ExplicitBarrelReleaseLetsTheNextPenContactDraw()
    {
        var gestureArmed = true;
        var barrelButtonDownEventLatched = true;

        Assert.True(CanvasPointerFeedback.RightButtonGestureIntent(
            gestureArmed,
            barrelButtonDownEventLatched,
            barrelButtonPressedInSnapshot: false));

        gestureArmed = false;
        barrelButtonDownEventLatched = false;

        Assert.False(CanvasPointerFeedback.RightButtonGestureIntent(
            gestureArmed,
            barrelButtonDownEventLatched,
            barrelButtonPressedInSnapshot: false));
        Assert.True(CanvasPointerFeedback.StylusDownStartsDrawing(
            inAir: false,
            rightButtonGestureIntent: false));
    }
}
