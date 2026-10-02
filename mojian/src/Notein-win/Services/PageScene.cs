using Notein_win.Models;
using System.Windows;

namespace Notein_win.Services;

// A source layer can contain images, ink and text. Sorting by the source layer
// before item order avoids flattening every import into three unrelated planes.
public static class PageScene
{
    public static bool ReorderObject(PageDocument document, PageObject item, int direction, bool edge)
    {
        string LayerId(object entry) => entry switch { StrokeRecord s => s.LayerId, TextItem t => t.LayerId, PageObject p => p.LayerId, _ => "" };
        var content = OrderedContent(document).Where(entry => LayerId(entry) == item.LayerId && entry is not PageObject { IsPageBackground: true }).ToList();
        var from = content.IndexOf(item);
        if (from < 0) return false;
        var to = edge ? direction > 0 ? content.Count - 1 : 0 : Math.Clamp(from + direction, 0, content.Count - 1);
        if (from == to) return false;
        content.RemoveAt(from); content.Insert(to, item);
        for (var i=0; i<content.Count; i++)
        {
            switch (content[i])
            {
                case StrokeRecord s: s.ZOrder=i; break;
                case TextItem t: t.ZOrder=i; break;
                case PageObject p: p.ZOrder=i; break;
            }
        }
        return true;
    }
    public static bool IsVisible(PageDocument document, string layerId) =>
        document.Layers.FirstOrDefault(layer => layer.Id == layerId)?.Visible ?? true;

    public static IEnumerable<object> OrderedContent(PageDocument document, Rect? viewport = null)
    {
        int LayerOrder(string id) => document.Layers.FirstOrDefault(layer => layer.Id == id)?.Order ?? int.MaxValue;
        return document.Objects.Select((item, index) =>
                (Item: (object)item, Layer: item.IsPageBackground ? int.MinValue : LayerOrder(item.LayerId), Order: item.ZOrder, Index: index, Visible: IsVisible(document, item.LayerId)))
            .Concat(document.Strokes.Select((item, index) =>
                (Item: (object)item, Layer: LayerOrder(item.LayerId), Order: item.ZOrder, Index: document.Objects.Count + index, Visible: IsVisible(document, item.LayerId))))
            .Concat(document.TextItems.Select((item, index) =>
                (Item: (object)item, Layer: LayerOrder(item.LayerId), Order: item.ZOrder, Index: document.Objects.Count + document.Strokes.Count + index, Visible: IsVisible(document, item.LayerId))))
            .Where(entry => entry.Visible && (viewport is not { } bounds || entry.Item switch
            {
                StrokeRecord stroke => stroke.Bounds.IntersectsWith(bounds),
                PageObject item => new Rect(item.X,item.Y,item.Width,item.Height).IntersectsWith(bounds),
                TextItem text => new Rect(text.X,text.Y,text.Width,text.Height).IntersectsWith(bounds),
                _ => true,
            }))
            .OrderBy(entry => entry.Layer).ThenBy(entry => entry.Order).ThenBy(entry => entry.Index)
            .Select(entry => entry.Item);
    }
}
