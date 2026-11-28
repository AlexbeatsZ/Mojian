using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Notein_win.Services;

/// <summary>
/// Names and ordering of the v3 folder library (docs/design/folder-library.md). Every client applies the
/// same rules as <c>tools/folder-library/convert.mjs</c>, so a title maps to the same file name everywhere.
/// </summary>
public static class FolderNames
{
    public const string PageExtension = ".mojian";
    public const string Descriptor = ".mojian-folder.json";
    public const string HiddenRoot = ".mojian";
    public const string AssetsRoot = ".mojian/assets";

    private static readonly Dictionary<char, char> FullWidth = new()
    {
        ['\\'] = '＼', ['/'] = '／', [':'] = '：', ['*'] = '＊', ['?'] = '？',
        ['"'] = '＂', ['<'] = '＜', ['>'] = '＞', ['|'] = '｜',
    };
    private static readonly Regex Reserved = new(@"^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>The file or folder name that stores a title on every supported file system.</summary>
    public static string Storable(string? title)
    {
        var builder = new StringBuilder();
        foreach (var rune in (title ?? string.Empty).EnumerateRunes())
        {
            if (rune.Value < 0x20 || rune.Value == 0x7f) continue;
            builder.Append(rune.IsBmp && FullWidth.TryGetValue((char)rune.Value, out var wide) ? wide.ToString() : rune.ToString());
        }
        var name = builder.ToString().Trim();
        if (name.StartsWith('.')) name = "．" + name[1..];
        if (name.EndsWith('.')) name = name[..^1] + "．";
        if (name.Length == 0) name = "无标题";
        if (Reserved.IsMatch(name)) name += "_";
        var runes = name.EnumerateRunes().ToArray();
        return runes.Length > 120 ? string.Concat(runes.Take(120).Select(rune => rune.ToString())).Trim() : name;
    }

    /// <summary>Explorer-style de-duplication, case-insensitive across pages and folders of one parent.</summary>
    public static string Unique(string baseName, string suffix, ISet<string> takenLowercase)
    {
        for (var n = 1; ; n++)
        {
            var candidate = (n == 1 ? baseName : $"{baseName} ({n})") + suffix;
            if (takenLowercase.Add(candidate.ToLowerInvariant())) return candidate;
        }
    }

    /// <summary>True when <paramref name="name"/> already stores <paramref name="title"/> (possibly with a de-dup suffix).</summary>
    public static bool Represents(string name, string? title, string suffix = "")
    {
        if (!name.EndsWith(suffix, StringComparison.Ordinal)) return false;
        var stem = name[..^suffix.Length];
        var storable = Storable(title);
        if (stem == storable) return true;
        var match = Regex.Match(stem, @"^(.*) \((\d+)\)$");
        return match.Success && match.Groups[1].Value == storable && int.Parse(match.Groups[2].Value) >= 2;
    }

    public static bool IsHidden(string name) => name.StartsWith('.');
}

/// <summary>Fractional ordering keys, a port of rocicorp/fractional-indexing (CC0-1.0); compared ordinally.</summary>
public static class FractionalIndex
{
    private const string Digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private const char Zero = '0';

    public static int Compare(string? a, string? b) => string.CompareOrdinal(a, b);

    private static string Midpoint(string a, string? b)
    {
        if (b != null && string.CompareOrdinal(a, b) >= 0) throw new ArgumentException($"{a} >= {b}");
        if ((a.Length > 0 && a[^1] == Zero) || (b is { Length: > 0 } && b[^1] == Zero)) throw new ArgumentException("trailing zero");
        if (!string.IsNullOrEmpty(b))
        {
            var n = 0;
            while ((n < a.Length ? a[n] : Zero) == b[n]) n++;
            if (n > 0) return b[..n] + Midpoint(a.Length > n ? a[n..] : string.Empty, b[n..]);
        }
        var digitA = a.Length > 0 ? Digits.IndexOf(a[0]) : 0;
        var digitB = b != null ? Digits.IndexOf(b[0]) : Digits.Length;
        if (digitB - digitA > 1) return Digits[(int)Math.Round(0.5 * (digitA + digitB), MidpointRounding.AwayFromZero)].ToString();
        if (b is { Length: > 1 }) return b[..1];
        return Digits[digitA] + Midpoint(a.Length > 1 ? a[1..] : string.Empty, null);
    }

    private static int IntegerLength(char head) => head is >= 'a' and <= 'z' ? head - 'a' + 2
        : head is >= 'A' and <= 'Z' ? 'Z' - head + 2 : throw new ArgumentException($"invalid order key head: {head}");

    private static string IntegerPart(string key)
    {
        var length = IntegerLength(key[0]);
        if (length > key.Length) throw new ArgumentException($"invalid order key: {key}");
        return key[..length];
    }

    public static bool IsValid(string? key)
    {
        if (string.IsNullOrEmpty(key) || key == "A" + new string(Zero, 26)) return false;
        try { var integer = IntegerPart(key); return key.Length == integer.Length || key[^1] != Zero; }
        catch (ArgumentException) { return false; }
    }

    private static string? Increment(string x)
    {
        var head = x[0];
        var digits = x[1..].ToCharArray().ToList();
        var carry = true;
        for (var i = digits.Count - 1; carry && i >= 0; i--)
        {
            var d = Digits.IndexOf(digits[i]) + 1;
            if (d == Digits.Length) digits[i] = Zero; else { digits[i] = Digits[d]; carry = false; }
        }
        if (!carry) return head + new string(digits.ToArray());
        if (head == 'Z') return "a" + Zero;
        if (head == 'z') return null;
        var next = (char)(head + 1);
        if (next > 'a') digits.Add(Zero); else digits.RemoveAt(digits.Count - 1);
        return next + new string(digits.ToArray());
    }

    private static string? Decrement(string x)
    {
        var head = x[0];
        var digits = x[1..].ToCharArray().ToList();
        var borrow = true;
        for (var i = digits.Count - 1; borrow && i >= 0; i--)
        {
            var d = Digits.IndexOf(digits[i]) - 1;
            if (d == -1) digits[i] = Digits[^1]; else { digits[i] = Digits[d]; borrow = false; }
        }
        if (!borrow) return head + new string(digits.ToArray());
        if (head == 'a') return "Z" + Digits[^1];
        if (head == 'A') return null;
        var next = (char)(head - 1);
        if (next < 'Z') digits.Add(Digits[^1]); else digits.RemoveAt(digits.Count - 1);
        return next + new string(digits.ToArray());
    }

    /// <summary>A key strictly between <paramref name="a"/> and <paramref name="b"/>; null is unbounded.</summary>
    public static string Between(string? a, string? b)
    {
        if (a != null && !IsValid(a)) throw new ArgumentException($"invalid order key: {a}");
        if (b != null && !IsValid(b)) throw new ArgumentException($"invalid order key: {b}");
        if (a != null && b != null && string.CompareOrdinal(a, b) >= 0) throw new ArgumentException($"{a} >= {b}");
        if (a == null)
        {
            if (b == null) return "a" + Zero;
            var ib = IntegerPart(b);
            var fb = b[ib.Length..];
            if (ib == "A" + new string(Zero, 26)) return ib + Midpoint(string.Empty, fb);
            if (string.CompareOrdinal(ib, b) < 0) return ib;
            return Decrement(ib) ?? throw new InvalidOperationException("cannot decrement any more");
        }
        if (b == null)
        {
            var ia = IntegerPart(a);
            return Increment(ia) ?? ia + Midpoint(a[ia.Length..], null);
        }
        var integerA = IntegerPart(a);
        var integerB = IntegerPart(b);
        if (integerA == integerB) return integerA + Midpoint(a[integerA.Length..], b[integerB.Length..]);
        var incremented = Increment(integerA) ?? throw new InvalidOperationException("cannot increment any more");
        return string.CompareOrdinal(incremented, b) < 0 ? incremented : integerA + Midpoint(a[integerA.Length..], null);
    }

    /// <summary>
    /// Keys for <paramref name="ordered"/> in display order, keeping every existing key that is still increasing
    /// so a reorder rewrites only the moved items.
    /// </summary>
    public static Dictionary<string, string> Assign(IReadOnlyList<string> ordered, IReadOnlyDictionary<string, string> existing)
    {
        var result = new Dictionary<string, string>();
        string? previous = null;
        for (var i = 0; i < ordered.Count; i++)
        {
            if (existing.TryGetValue(ordered[i], out var key) && IsValid(key) && (previous == null || string.CompareOrdinal(key, previous) > 0))
            {
                result[ordered[i]] = previous = key;
                continue;
            }
            string? next = null;
            for (var j = i + 1; j < ordered.Count && next == null; j++)
                if (existing.TryGetValue(ordered[j], out var later) && IsValid(later) && (previous == null || string.CompareOrdinal(later, previous) > 0)) next = later;
            result[ordered[i]] = previous = Between(previous, next);
        }
        return result;
    }
}

/// <summary>
/// The page file layout <c>{"version":3,"id","sortKey","page":{…},"content":{…}}</c>. <c>page</c> is always written
/// before <c>content</c>, so listing a library reads only each file's head.
/// </summary>
public static class PageFileFormat
{
    public sealed record Head(int Version, string? Id, string? SortKey, JsonObject? Page);

    public static Head ReadHead(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 16 * 1024);
        var buffer = new byte[16 * 1024];
        var length = 0;
        while (true)
        {
            var read = stream.Read(buffer, length, buffer.Length - length);
            length += read;
            var final = read == 0 || length == stream.Length;
            var head = TryParseHead(buffer.AsSpan(0, length), final);
            if (head != null) return head;
            if (final) throw new InvalidDataException($"页面文件格式无效：{Path.GetFileName(path)}");
            if (length == buffer.Length) Array.Resize(ref buffer, buffer.Length * 2);
        }
    }

    private static Head? TryParseHead(ReadOnlySpan<byte> data, bool final)
    {
        var reader = new Utf8JsonReader(data, isFinalBlock: final, state: default);
        try
        {
            if (!reader.Read()) return null;
            if (reader.TokenType != JsonTokenType.StartObject) throw new InvalidDataException("页面文件不是 JSON 对象");
            int version = 0; string? id = null, sortKey = null; JsonObject? page = null;
            while (true)
            {
                if (!reader.Read()) return null;
                if (reader.TokenType == JsonTokenType.EndObject) return new Head(version, id, sortKey, page);
                var name = reader.GetString();
                if (name == "content") return new Head(version, id, sortKey, page);
                if (!reader.Read()) return null;
                switch (name)
                {
                    case "version" when reader.TokenType == JsonTokenType.Number: version = reader.GetInt32(); break;
                    case "id" when reader.TokenType == JsonTokenType.String: id = reader.GetString(); break;
                    case "sortKey" when reader.TokenType == JsonTokenType.String: sortKey = reader.GetString(); break;
                    case "page" when reader.TokenType == JsonTokenType.StartObject:
                        var start = (int)reader.TokenStartIndex;
                        if (!reader.TrySkip()) return null;
                        page = JsonNode.Parse(data[start..(int)reader.BytesConsumed])!.AsObject();
                        break;
                    default:
                        if (!reader.TrySkip()) return null;
                        break;
                }
            }
        }
        catch (JsonException) when (!final) { return null; }
    }

    /// <summary>The raw <c>content</c> value bytes of a page file, without parsing strokes.</summary>
    public static ReadOnlyMemory<byte> ReadContent(byte[] file)
    {
        var reader = new Utf8JsonReader(file);
        reader.Read();
        while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
        {
            var name = reader.GetString();
            reader.Read();
            if (name == "content")
            {
                var start = (int)reader.TokenStartIndex;
                reader.Skip();
                return file.AsMemory(start, (int)reader.BytesConsumed - start);
            }
            reader.Skip();
        }
        return "{}"u8.ToArray();
    }

    /// <summary>A complete page file from its head and raw content bytes; the head is written first.</summary>
    public static byte[] Compose(string id, string sortKey, JsonObject page, ReadOnlySpan<byte> content)
    {
        using var buffer = new MemoryStream(content.Length + 1024);
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteNumber("version", 3);
            writer.WriteString("id", id);
            writer.WriteString("sortKey", sortKey);
            writer.WritePropertyName("page");
            page.WriteTo(writer);
            writer.WritePropertyName("content");
            writer.WriteRawValue(content, skipInputValidation: true);
            writer.WriteEndObject();
        }
        return buffer.ToArray();
    }
}
