using System.Windows.Input;
using Notein_win.Models;

namespace Notein_win.Tests;

public class ShortcutModelsTests
{
    [Fact]
    public void GestureRoundTripsWithModifiers()
    {
        var source = new ShortcutGesture(Key.PageDown, ModifierKeys.Control | ModifierKeys.Shift);

        Assert.True(ShortcutGesture.TryParse(source.Serialize(), out var restored));
        Assert.Equal(source, restored);
        Assert.Equal("Ctrl + Shift + PageDown", restored.DisplayText);
    }

    [Fact]
    public void CatalogUsesConcreteTagChildrenAndDynamicTools()
    {
        var actions = ShortcutActionCatalog.Build(ToolShelf.Defaults());

        Assert.Contains(actions, action => action.Id == "home:tag-important");
        Assert.Contains(actions, action => action.Id == "home:tag-question");
        Assert.DoesNotContain(actions, action => action.Id == "home:tag");
        Assert.Contains(actions, action => action.Id == "tool:pen-black");
    }

    [Fact]
    public void DefaultsDoNotReuseGestures()
    {
        var gestures = ShortcutActionCatalog.DefaultBindings().Values
            .Select(value => ShortcutGesture.TryParse(value, out var gesture) ? gesture : default)
            .ToList();

        Assert.All(gestures, gesture => Assert.True(gesture.IsValid));
        Assert.Equal(gestures.Count, gestures.Distinct().Count());
    }
}
