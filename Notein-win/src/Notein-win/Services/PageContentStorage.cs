using System.IO;
using System.Text.Json.Nodes;
using Notein_win.Models;

namespace Notein_win.Services;

public class PageContentStorage
{
    public string RootDirectory { get; }

    public PageContentStorage(string? rootDirectory = null)
    {
        RootDirectory = rootDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Notein-win",
            "Storage"
        );
        Directory.CreateDirectory(RootDirectory);
    }

    public string GetNotebookDirectory(string notebookId)
    {
        var path = Path.Combine(RootDirectory, SafeId(notebookId));
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetPageDirectory(string notebookId, string pageId)
    {
        var path = Path.Combine(GetNotebookDirectory(notebookId), "pages", SafeId(pageId));
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetAssetDirectory(string notebookId, string pageId)
    {
        var path = Path.Combine(GetPageDirectory(notebookId, pageId), "assets");
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetPageDocumentPath(string notebookId, string pageId) =>
        Path.Combine(GetPageDirectory(notebookId, pageId), "page.json");

    public PageInsertDocument LoadPageDocument(string notebookId, string pageId)
    {
        var file = GetPageDocumentPath(notebookId, pageId);
        if (!File.Exists(file))
        {
            return new PageInsertDocument(notebookId, pageId);
        }

        try
        {
            var content = File.ReadAllText(file);
            var node = JsonNode.Parse(content) as JsonObject;
            if (node != null)
            {
                return PageInsertDocument.FromJson(node);
            }
        }
        catch
        {
            // fallback
        }

        return new PageInsertDocument(notebookId, pageId);
    }

    public void SavePageDocument(PageInsertDocument doc)
    {
        var file = GetPageDocumentPath(doc.NotebookId, doc.PageId);
        var json = doc.ToJson().ToJsonString();
        File.WriteAllText(file, json);
    }

    public static string SafeId(string id) =>
        string.Concat(id.Select(c => char.IsLetterOrDigit(c) || c == '-' || c == '_' ? c : '_'));
}
