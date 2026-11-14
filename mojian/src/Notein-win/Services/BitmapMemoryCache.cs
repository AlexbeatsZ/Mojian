using System.Collections;
using System.Windows.Media.Imaging;

namespace Notein_win.Services;

internal sealed class BitmapMemoryCache(long byteBudget) : IEnumerable<KeyValuePair<string, BitmapSource>>
{
    private readonly LinkedList<KeyValuePair<string, BitmapSource>> _recent = new();
    private readonly Dictionary<string, LinkedListNode<KeyValuePair<string, BitmapSource>>> _entries = new(StringComparer.OrdinalIgnoreCase);
    public long Bytes { get; private set; }
    private static long Cost(BitmapSource bitmap) => (long)bitmap.PixelHeight * (((long)bitmap.PixelWidth * Math.Max(8, bitmap.Format.BitsPerPixel) + 7) / 8);

    public bool TryGetValue(string key, out BitmapSource value)
    {
        if (!_entries.TryGetValue(key, out var node)) { value = null!; return false; }
        _recent.Remove(node); _recent.AddFirst(node);
        value = node.Value.Value;
        return true;
    }
    public BitmapSource this[string key]
    {
        set
        {
            if (_entries.Remove(key, out var old)) { _recent.Remove(old); Bytes -= Cost(old.Value.Value); }
            var cost = Cost(value);
            if (cost > byteBudget) return;
            while (Bytes + cost > byteBudget && _recent.Last is { } last)
            {
                _recent.RemoveLast(); _entries.Remove(last.Value.Key); Bytes -= Cost(last.Value.Value);
            }
            _entries[key] = _recent.AddFirst(new KeyValuePair<string, BitmapSource>(key, value)); Bytes += cost;
        }
    }
    public void Clear() { _entries.Clear(); _recent.Clear(); Bytes = 0; }
    public IEnumerator<KeyValuePair<string, BitmapSource>> GetEnumerator() => _recent.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}
