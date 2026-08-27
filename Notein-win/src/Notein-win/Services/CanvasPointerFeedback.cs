using Notein_win.Models;

namespace Notein_win.Services;

public enum CanvasPointerCursorMode
{
    Arrow,
    Text,
    Hand,
    PenDot,
}

public static class CanvasPointerFeedback
{
    public static CanvasPointerCursorMode Resolve(
        bool panoramaHandEnabled,
        bool textInsertionArmed,
        bool stylusInRange,
        DrawingToolKind? selectedTool)
    {
        if (panoramaHandEnabled) return CanvasPointerCursorMode.Hand;
        if (stylusInRange && selectedTool is DrawingToolKind.Pen or DrawingToolKind.Highlighter)
            return CanvasPointerCursorMode.PenDot;
        if (textInsertionArmed) return CanvasPointerCursorMode.Text;
        return CanvasPointerCursorMode.Arrow;
    }
}
