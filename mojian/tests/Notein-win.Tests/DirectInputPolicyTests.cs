using Notein_win.Services;
using Notein_win.Models;
using System.Windows.Input;
using Xunit;

namespace Notein_win.Tests;

public class DirectInputPolicyTests
{
    [Fact]
    public void DirectPointersTransformOnlyAnExistingSelection()
    {
        Assert.Equal(
            DirectPointerDownIntent.MarqueeCandidate,
            DirectInputPolicy.PointerDownIntent(1, true, false, false, false, false));
        Assert.Equal(
            DirectPointerDownIntent.TransformSelection,
            DirectInputPolicy.PointerDownIntent(1, true, false, true, false, false));
    }

    [Fact]
    public void DragInsideSelectionFrameGapMovesInsteadOfMarquee()
    {
        Assert.Equal(
            DirectPointerDownIntent.TransformSelection,
            DirectInputPolicy.PointerDownIntent(1, true, false, false, false, true));
        Assert.Equal(
            DirectPointerDownIntent.NativeTextEdit,
            DirectInputPolicy.PointerDownIntent(1, true, true, false, false, true));
    }

    [Fact]
    public void SecondFingerAlwaysOwnsTheViewport()
    {
        Assert.Equal(
            DirectPointerDownIntent.PageNavigation,
            DirectInputPolicy.PointerDownIntent(2, true, true, true, true, true));
    }

    [Fact]
    public void EditingTextRetainsNativeEditorHandling()
    {
        Assert.Equal(
            DirectPointerDownIntent.NativeTextEdit,
            DirectInputPolicy.PointerDownIntent(1, true, true, true, false, false));
    }

    [Fact]
    public void InsertSpaceUsesFingerOrPenDragAndPenOrMouseHover()
    {
        Assert.True(DirectInputPolicy.CanDragInsertSpace(DirectInputDevice.Finger));
        Assert.False(DirectInputPolicy.CanDragInsertSpace(DirectInputDevice.Mouse));
        Assert.True(DirectInputPolicy.CanDragInsertSpace(DirectInputDevice.Pen));
        Assert.False(DirectInputPolicy.ShowsInsertSpaceHover(DirectInputDevice.Finger));
        Assert.True(DirectInputPolicy.ShowsInsertSpaceHover(DirectInputDevice.Mouse));
        Assert.True(DirectInputPolicy.ShowsInsertSpaceHover(DirectInputDevice.Pen));
    }

    [Fact]
    public void PenAndHighlighterDragAnExistingSelectionButOutsideContactWrites()
    {
        Assert.True(DirectInputPolicy.PenContactTransformsSelection(DrawingToolKind.Pen, false, true));
        Assert.True(DirectInputPolicy.PenContactTransformsSelection(DrawingToolKind.Highlighter, false, true));
        Assert.False(DirectInputPolicy.PenContactTransformsSelection(DrawingToolKind.Pen, false, false));
        Assert.False(DirectInputPolicy.PenContactTransformsSelection(DrawingToolKind.Lasso, false, true));
        Assert.False(DirectInputPolicy.PenContactTransformsSelection(DrawingToolKind.Pen, true, true));
    }

    [Theory]
    [InlineData(Key.Delete)]
    [InlineData(Key.Back)]
    public void DeleteAndBackspaceDeleteCanvasSelection(Key key)
    {
        Assert.True(DirectInputPolicy.IsSelectionDeleteKey(key, ModifierKeys.None));
        Assert.False(DirectInputPolicy.IsSelectionDeleteKey(key, ModifierKeys.Control));
    }
}
