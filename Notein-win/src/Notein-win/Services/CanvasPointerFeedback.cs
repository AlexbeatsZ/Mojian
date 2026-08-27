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
