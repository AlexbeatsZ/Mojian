using System.Windows.Input;
using Notein_win.Models;

namespace Notein_win.Services;

public enum DirectPointerDownIntent
{
    Ignore,
    NativeTextEdit,
    TransformSelection,
    MarqueeCandidate,
    PageNavigation,
}

public enum DirectInputDevice
{
    Finger,
    Mouse,
    Pen,
}

public static class DirectInputPolicy
{
    public static bool IsSelectionDeleteKey(Key key, ModifierKeys modifiers) =>
        modifiers == ModifierKeys.None && key is Key.Delete or Key.Back;

    public static bool CanDragInsertSpace(DirectInputDevice device) =>
        device is DirectInputDevice.Finger or DirectInputDevice.Pen;

    public static bool ShowsInsertSpaceHover(DirectInputDevice device) =>
        device is DirectInputDevice.Mouse or DirectInputDevice.Pen;

    public static bool PenContactTransformsSelection(
        DrawingToolKind toolKind,
        bool forceErase,
        bool selectionBoundsHit) =>
        !forceErase &&
        selectionBoundsHit &&
        toolKind is DrawingToolKind.Pen or DrawingToolKind.Highlighter;

    public static DirectPointerDownIntent PointerDownIntent(
        int pointerCount,
        bool primaryDirectPointer,
        bool editingTextHit,
        bool selectedContentHit,
        bool selectionHandleHit) =>
        pointerCount >= 2 ? DirectPointerDownIntent.PageNavigation :
        !primaryDirectPointer ? DirectPointerDownIntent.Ignore :
        editingTextHit ? DirectPointerDownIntent.NativeTextEdit :
        selectedContentHit || selectionHandleHit ? DirectPointerDownIntent.TransformSelection :
        DirectPointerDownIntent.MarqueeCandidate;
}
