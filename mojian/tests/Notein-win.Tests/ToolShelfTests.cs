using Notein_win.Models;
using Xunit;

namespace Notein_win.Tests;

public class ToolShelfTests
{
    [Fact]
    public void ToolShelf_Defaults_ContainRequiredTools()
    {
        var shelf = new ToolShelf();

        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Pen && t.Color == InkPalette.Black);
        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Pen && t.Color == InkPalette.Blue);
        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Pen && t.Color == InkPalette.Red);
        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Highlighter);
        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Eraser);
        Assert.Contains(shelf.Tools, t => t.Kind == DrawingToolKind.Lasso);
        Assert.True(shelf.Tools.Single(t => t.Kind == DrawingToolKind.Eraser).PressureEraseEnabled);
    }

    [Fact]
    public void ToolShelf_AddsToolsAndRestoresMissingDefaults()
    {
        var shelf = new ToolShelf();
        int initialCount = shelf.Tools.Count;

        var newTool = shelf.Add(DrawingToolKind.Pen);
        Assert.Equal(initialCount + 1, shelf.Tools.Count);
        Assert.Equal(newTool.Id, shelf.SelectedId);

        var restored = new ToolShelf(new[] { new ToolPreset("pen-black", DrawingToolKind.Pen) });
        Assert.Equal(ToolShelf.Defaults().Count(), restored.Tools.Count);
        Assert.Contains(restored.Tools, tool => tool.Id == "lasso-freeform");
        Assert.Contains(restored.Tools, tool => tool.Id == "eraser-stroke");
    }

    [Fact]
    public void ToolShelf_JsonSerialization_RoundTripsAccurately()
    {
        var shelf = new ToolShelf();
        shelf.Select("lasso-freeform");
        shelf.Selected.LassoShape = LassoShape.Rectangle;

        var json = shelf.ToJson();
        Assert.NotNull(json);

        var restored = ToolShelf.FromJson(json);
        Assert.NotNull(restored);
        Assert.Equal(shelf.Tools.Count, restored.Tools.Count);
        Assert.Equal("lasso-freeform", restored.SelectedId);
        Assert.Equal(LassoShape.Rectangle, restored.Selected.LassoShape);
    }

    [Fact]
    public void EraserRadiusJsonIsClampedToExpandedCrossPlatformRange()
    {
        var tooSmall = ToolPreset.FromJson(new System.Text.Json.Nodes.JsonObject
        {
            ["id"] = "eraser-small",
            ["kind"] = "Eraser",
            ["eraserRadiusMm"] = 0.01f,
        });
        var tooLarge = ToolPreset.FromJson(new System.Text.Json.Nodes.JsonObject
        {
            ["id"] = "eraser-large",
            ["kind"] = "Eraser",
            ["eraserRadiusMm"] = 100f,
        });

        Assert.Equal(ToolPreset.MinimumEraserRadiusMm, tooSmall!.EraserRadiusMm);
        Assert.Equal(ToolPreset.MaximumEraserRadiusMm, tooLarge!.EraserRadiusMm);
    }
}
