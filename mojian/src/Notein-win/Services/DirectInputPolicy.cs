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

/// <summary>Reading view owns the single-pointer stream before content can be selected or edited.</summary>
public enum ReadingViewPointerIntent
{
    Pan,
    PassThrough,
    Block,
}

public static class DirectInputPolicy
{
    public static bool IsSelectionDeleteKey(Key key, ModifierKeys modifiers) =>
        modifiers == ModifierKeys.None && key is Key.Delete or Key.Back;

    public static bool CanDragInsertSpace(DirectInputDevice device) =>
        device is DirectInputDevice.Finger or DirectInputDevice.Pen or DirectInputDevice.Mouse;

    public static bool ShowsInsertSpaceHover(DirectInputDevice device) =>
        device is DirectInputDevice.Mouse or DirectInputDevice.Pen;

    /// <summary>
    /// Finger, pen and left-mouse drags pan; middle-button navigation keeps its own pan/zoom
    /// path; right-button menus and lassos are suppressed because both select content.
    /// </summary>
    public static ReadingViewPointerIntent ReadingViewPointerIntent(DirectInputDevice device, MouseButton? mouseButton) =>
        device != DirectInputDevice.Mouse ? Services.ReadingViewPointerIntent.Pan :
        mouseButton switch
        {
            MouseButton.Middle => Services.ReadingViewPointerIntent.PassThrough,
            MouseButton.Right => Services.ReadingViewPointerIntent.Block,
            MouseButton.Left => Services.ReadingViewPointerIntent.Pan,
            _ => Services.ReadingViewPointerIntent.Block,
        };

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
        bool selectionHandleHit,
        bool selectionFrameHit) =>
        pointerCount >= 2 ? DirectPointerDownIntent.PageNavigation :
        !primaryDirectPointer ? DirectPointerDownIntent.Ignore :
        editingTextHit ? DirectPointerDownIntent.NativeTextEdit :
        selectedContentHit || selectionHandleHit || selectionFrameHit ? DirectPointerDownIntent.TransformSelection :
        DirectPointerDownIntent.MarqueeCandidate;
}
