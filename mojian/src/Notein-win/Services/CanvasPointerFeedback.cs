using Notein_win.Models;

namespace Notein_win.Services;

public enum CanvasPointerCursorMode
{
    Arrow,
    Hand,
    PenDot,
}

public static class CanvasPointerFeedback
{
    public static bool RefreshHoverFromPromotedMouse(
        bool promotedByStylus,
        bool stylusContactActive) =>
        promotedByStylus && !stylusContactActive;

    public static bool PromotedHoverDrivesMiddleNavigation(
        bool promotedByStylus,
        bool stylusContactActive,
        bool middleNavigationActive,
        bool middleNavigationUsesStylus) =>
        promotedByStylus &&
        !stylusContactActive &&
        middleNavigationActive &&
        middleNavigationUsesStylus;

    public static bool ContactStylusDrivesMiddleNavigation(
        bool stylusContactActive,
        bool middleNavigationActive,
        bool middleNavigationUsesStylus) =>
        stylusContactActive && middleNavigationActive && middleNavigationUsesStylus;

    public static bool NavigationUsesRadialZoom(bool controlPressed, bool zoomLocked) =>
        controlPressed && !zoomLocked;

    public static bool RightButtonMovementStartsLasso(double movementDistance, double dragThreshold) =>
        movementDistance >= Math.Max(0, dragThreshold);

    public static bool StylusBarrelHoldStartsLasso(
        bool hasStylusContact,
        long elapsedMilliseconds,
        int clickWindowMilliseconds) =>
        hasStylusContact &&
        Math.Max(0, elapsedMilliseconds) >= Math.Max(1, clickWindowMilliseconds);

    public static bool RightButtonUsesStylus(
        bool promotedByStylus,
        bool barrelButtonDown,
        bool matchesStylusHover) =>
        promotedByStylus || barrelButtonDown || matchesStylusHover;

    public static bool RightButtonGestureIntent(
        bool gestureArmed,
        bool barrelButtonDownEventLatched,
        bool barrelButtonPressedInSnapshot) =>
        gestureArmed || barrelButtonDownEventLatched || barrelButtonPressedInSnapshot;

    public static bool IgnoreStalePromotedStylusRightDown(
        bool promotedByStylus,
        bool gestureArmed,
        bool barrelButtonDownEventLatched,
        bool barrelButtonPressedInSnapshot) =>
        promotedByStylus &&
        !gestureArmed &&
        !barrelButtonDownEventLatched &&
        !barrelButtonPressedInSnapshot;

    public static bool StylusUpEndsRightButtonGesture(
        bool gestureArmed,
        bool usesStylus,
        bool barrelButtonPressedInSnapshot) =>
        gestureArmed && usesStylus && !barrelButtonPressedInSnapshot;

    public static bool StylusDownStartsDrawing(bool inAir, bool rightButtonGestureIntent) =>
        !inAir && !rightButtonGestureIntent;

    public static bool SuppressMouseStream(
        bool stylusContactActive,
        bool promotedByStylus,
        bool stylusDrivenMiddleNavigation) =>
        stylusContactActive || promotedByStylus || stylusDrivenMiddleNavigation;

    public static bool UseLatestStylusSample(
        bool panning,
        bool dragZooming,
        bool stylusDrivenMiddleNavigation) =>
        panning || dragZooming || stylusDrivenMiddleNavigation;

    public static CanvasPointerCursorMode Resolve(
        bool penHandEnabled,
        bool stylusInRange,
        DrawingToolKind? selectedTool)
    {
        if (stylusInRange && penHandEnabled) return CanvasPointerCursorMode.Hand;
        if (stylusInRange && selectedTool is DrawingToolKind.Pen or DrawingToolKind.Highlighter)
            return CanvasPointerCursorMode.PenDot;
        return CanvasPointerCursorMode.Arrow;
    }
}
