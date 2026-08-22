using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Xml.Linq;

namespace Notein_win.Services;

public static class XlsxPreviewService
{
    private static readonly XNamespace SpreadsheetNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static readonly XNamespace RelationshipsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static readonly XNamespace PackageRelationshipsNs = "http://schemas.openxmlformats.org/package/2006/relationships";

    public static List<List<string>> ReadFirstSheet(string path, int maxRows = 12, int maxColumns = 8)
    {
        using var archive = ZipFile.OpenRead(path);
        var sharedStrings = ReadSharedStrings(archive);
        var workbook = LoadXml(archive, "xl/workbook.xml");
        var firstSheet = workbook.Root?.Element(SpreadsheetNs + "sheets")?.Elements(SpreadsheetNs + "sheet").FirstOrDefault()
            ?? throw new InvalidDataException("工作簿没有工作表。");
        var relationshipId = (string?)firstSheet.Attribute(RelationshipsNs + "id")
            ?? throw new InvalidDataException("工作表关系无效。");
        var relationships = LoadXml(archive, "xl/_rels/workbook.xml.rels");
        var target = relationships.Root?.Elements(PackageRelationshipsNs + "Relationship")
            .FirstOrDefault(item => (string?)item.Attribute("Id") == relationshipId)?.Attribute("Target")?.Value
            ?? throw new InvalidDataException("找不到第一个工作表文件。");
        var worksheetPath = NormalizeZipPath("xl", target);
        var worksheet = LoadXml(archive, worksheetPath);
        var result = new List<List<string>>();
        foreach (var row in worksheet.Descendants(SpreadsheetNs + "row").Take(maxRows))
        {
            var values = Enumerable.Repeat(string.Empty, maxColumns).ToList();
            foreach (var cell in row.Elements(SpreadsheetNs + "c"))
            {
                var reference = (string?)cell.Attribute("r") ?? string.Empty;
                var column = ColumnIndex(reference);
                if (column < 0 || column >= maxColumns) continue;
                var type = (string?)cell.Attribute("t");
                var raw = cell.Element(SpreadsheetNs + "v")?.Value ?? cell.Element(SpreadsheetNs + "is")?.Element(SpreadsheetNs + "t")?.Value ?? string.Empty;
                values[column] = type == "s" && int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var index) && index >= 0 && index < sharedStrings.Count
                    ? sharedStrings[index]
                    : raw;
            }
            result.Add(values);
        }
        return result.Count == 0 ? new List<List<string>> { new() { "（空工作表）" } } : result;
    }

    private static List<string> ReadSharedStrings(ZipArchive archive)
    {
        var entry = archive.GetEntry("xl/sharedStrings.xml");
        if (entry == null) return new List<string>();
        using var stream = entry.Open();
        var document = XDocument.Load(stream);
        return document.Descendants(SpreadsheetNs + "si")
            .Select(item => string.Concat(item.Descendants(SpreadsheetNs + "t").Select(text => text.Value)))
            .ToList();
    }

    private static XDocument LoadXml(ZipArchive archive, string path)
    {
        var entry = archive.GetEntry(path.Replace('\\', '/')) ?? throw new InvalidDataException($"缺少 {path}。");
        using var stream = entry.Open();
        return XDocument.Load(stream);
    }

    private static string NormalizeZipPath(string root, string target)
    {
        if (target.StartsWith('/')) return target.TrimStart('/');
        var pieces = new List<string>(root.Split('/', StringSplitOptions.RemoveEmptyEntries));
        foreach (var piece in target.Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            if (piece == ".." && pieces.Count > 0) pieces.RemoveAt(pieces.Count - 1);
            else if (piece != ".") pieces.Add(piece);
        }
        return string.Join('/', pieces);
    }

    private static int ColumnIndex(string reference)
    {
        var value = 0;
        var found = false;
        foreach (var character in reference)
        {
            if (!char.IsLetter(character)) break;
            found = true;
            value = value * 26 + (char.ToUpperInvariant(character) - 'A' + 1);
        }
        return found ? value - 1 : -1;
    }
}
