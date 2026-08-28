using System.IO;
using System.Text.Json;
using Notein_win.Models;

namespace Notein_win.Services;

public sealed class WorkspaceStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };
    private static readonly JsonSerializerOptions PageJsonOptions = new(JsonOptions)
    {
        WriteIndented = false,
    };

    public string RootDirectory { get; }
    public string LibraryPath => Path.Combine(RootDirectory, "library.json");
    public string SettingsPath => Path.Combine(RootDirectory, "settings.json");

    public static string DefaultRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Mojian",
        "Workspace");

    public WorkspaceStore(string? rootDirectory = null)
    {
        RootDirectory = Path.GetFullPath(string.IsNullOrWhiteSpace(rootDirectory) ? DefaultRoot : rootDirectory);
        Directory.CreateDirectory(RootDirectory);
        Directory.CreateDirectory(Path.Combine(RootDirectory, "notebooks"));
    }

    public NotebookLibrary LoadLibrary()
    {
        if (!File.Exists(LibraryPath))
        {
            var created = NotebookLibrary.Sample();
            SaveLibrary(created);
            SaveWelcomePage(created.ActiveNotebook);
            return created;
        }

        try
        {
            var library = JsonSerializer.Deserialize<NotebookLibrary>(File.ReadAllText(LibraryPath), JsonOptions);
            if (library?.Notebooks.Count > 0)
            {
                if (library.Notebooks.All(notebook => notebook.Id != library.ActiveNotebookId))
                {
                    library.ActiveNotebookId = library.Notebooks[0].Id;
                }
                NormalizeSelections(library);
                return library;
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            PreserveCorruptFile(LibraryPath);
        }

        var recovered = NotebookLibrary.Sample();
        SaveLibrary(recovered);
        SaveWelcomePage(recovered.ActiveNotebook);
        return recovered;
    }

    public void SaveLibrary(NotebookLibrary library) => AtomicWrite(LibraryPath, JsonSerializer.Serialize(library, JsonOptions));

    public WorkspaceSettings LoadSettings()
    {
        if (!File.Exists(SettingsPath)) return new WorkspaceSettings();
        try
        {
            return JsonSerializer.Deserialize<WorkspaceSettings>(File.ReadAllText(SettingsPath), JsonOptions) ?? new WorkspaceSettings();
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            PreserveCorruptFile(SettingsPath);
            return new WorkspaceSettings();
        }
    }

    public void SaveSettings(WorkspaceSettings settings) => AtomicWrite(SettingsPath, JsonSerializer.Serialize(settings, JsonOptions));

    public PageDocument LoadPage(string notebookId, string pageId)
    {
        var path = GetPageDocumentPath(notebookId, pageId);
        if (!File.Exists(path)) return new PageDocument(notebookId, pageId);
        try
        {
            using var stream = File.OpenRead(path);
            var document = JsonSerializer.Deserialize<PageDocument>(stream, PageJsonOptions);
            if (document != null)
            {
                document.NotebookId = notebookId;
                document.PageId = pageId;
                return document;
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            PreserveCorruptFile(path);
        }
        return new PageDocument(notebookId, pageId);
    }

    public void SavePage(PageDocument document)
    {
        document.Revision++;
        document.UpdatedAt = DateTime.UtcNow;
        AtomicWrite(GetPageDocumentPath(document.NotebookId, document.PageId), JsonSerializer.SerializeToUtf8Bytes(document, PageJsonOptions));
    }

    public string GetNotebookDirectory(string notebookId)
    {
        var path = Path.Combine(RootDirectory, "notebooks", SafeId(notebookId));
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetPageDirectory(string notebookId, string pageId)
    {
        var path = Path.Combine(GetNotebookDirectory(notebookId), "pages");
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetAssetDirectory(string notebookId, string pageId)
    {
        var path = Path.Combine(GetNotebookDirectory(notebookId), "assets", SafeId(pageId));
        Directory.CreateDirectory(path);
        return path;
    }

    public string GetPageDocumentPath(string notebookId, string pageId) => Path.Combine(GetPageDirectory(notebookId, pageId), $"{SafeId(pageId)}.json");

    public string ResolveAsset(string notebookId, string pageId, string assetName) =>
        Path.Combine(GetAssetDirectory(notebookId, pageId), SafeAssetName(assetName));

    public string ImportAsset(string notebookId, string pageId, string sourcePath)
    {
        if (!File.Exists(sourcePath)) throw new FileNotFoundException("找不到要插入的文件。", sourcePath);
        var extension = Path.GetExtension(sourcePath);
        var assetName = $"asset-{Guid.NewGuid():N}{extension.ToLowerInvariant()}";
        var destination = ResolveAsset(notebookId, pageId, assetName);
        File.Copy(sourcePath, destination, overwrite: false);
        return assetName;
    }

    public string WriteAsset(string notebookId, string pageId, string extension, ReadOnlySpan<byte> bytes)
    {
        var normalizedExtension = extension.StartsWith('.') ? extension : $".{extension}";
        var assetName = $"asset-{Guid.NewGuid():N}{normalizedExtension.ToLowerInvariant()}";
        File.WriteAllBytes(ResolveAsset(notebookId, pageId, assetName), bytes.ToArray());
        return assetName;
    }

    public void CopyPageData(string sourceNotebookId, string sourcePageId, string targetNotebookId, string targetPageId)
    {
        var sourceDocument = LoadPage(sourceNotebookId, sourcePageId);
        sourceDocument.NotebookId = targetNotebookId;
        sourceDocument.PageId = targetPageId;
        sourceDocument.Revision = 0;

        var sourceAssets = GetAssetDirectory(sourceNotebookId, sourcePageId);
        var targetAssets = GetAssetDirectory(targetNotebookId, targetPageId);
        foreach (var source in Directory.EnumerateFiles(sourceAssets, "*", SearchOption.AllDirectories))
        {
            var relative = Path.GetRelativePath(sourceAssets, source);
            var destination = Path.Combine(targetAssets, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.Copy(source, destination, overwrite: false);
        }
        SavePage(sourceDocument);
    }

    public void DeletePageData(string notebookId, string pageId)
    {
        var documentPath = GetPageDocumentPath(notebookId, pageId);
        if (File.Exists(documentPath)) File.Delete(documentPath);
        var assetDirectory = GetAssetDirectory(notebookId, pageId);
        if (Directory.Exists(assetDirectory)) Directory.Delete(assetDirectory, recursive: true);
    }

    public static string SafeId(string id)
    {
        var safe = new string(id.Where(character => char.IsLetterOrDigit(character) || character is '-' or '_').ToArray());
        return string.IsNullOrWhiteSpace(safe) ? "invalid" : safe;
    }

    public static string SafeAssetName(string name)
    {
        var file = Path.GetFileName(name);
        return string.IsNullOrWhiteSpace(file) ? "missing.asset" : file;
    }

    public static void AtomicWrite(string path, string content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + $".{Guid.NewGuid():N}.tmp";
        File.WriteAllText(temporary, content);
        File.Move(temporary, path, overwrite: true);
    }

    public static void AtomicWrite(string path, ReadOnlySpan<byte> content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + $".{Guid.NewGuid():N}.tmp";
        File.WriteAllBytes(temporary, content.ToArray());
        File.Move(temporary, path, overwrite: true);
    }

    private void SaveWelcomePage(NotebookWorkspace notebook)
    {
        var page = notebook.SelectedPage();
        if (page == null) return;
        var doc = new PageDocument(notebook.Id, page.Id);
        doc.TextItems.Add(new TextItem
        {
            X = 72,
            Y = 110,
            Width = 520,
            Height = 160,
            FontSize = 22,
            Bold = true,
            Text = "这是你的本地工作区\n\n用鼠标或手写笔直接书写。所有页面与附件都会保存到你选择的文件夹，可交给 Syncthing 同步。",
        });
        SavePage(doc);
    }

    private static void NormalizeSelections(NotebookLibrary library)
    {
        foreach (var notebook in library.Notebooks)
        {
            if (notebook.FocusedSectionId != null && notebook.FindSection(notebook.FocusedSectionId) == null)
            {
                notebook.FocusedSectionId = notebook.FlattenAllSections().FirstOrDefault()?.Id;
            }
            if (notebook.SelectedPage() == null) notebook.SelectFirstPage(PagePaneMode.CurrentSection);
        }
    }

    private static void PreserveCorruptFile(string path)
    {
        if (!File.Exists(path)) return;
        var backup = $"{path}.corrupt-{DateTime.UtcNow:yyyyMMdd-HHmmss}";
        File.Move(path, backup, overwrite: false);
    }
}

public sealed class PreferencesStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true,
    };

    public string FilePath { get; }

    public PreferencesStore(string? filePath = null)
    {
        FilePath = filePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Mojian",
            "preferences.json");
    }

    public AppPreferences Load()
    {
        if (!File.Exists(FilePath)) return new AppPreferences();
        try
        {
            return JsonSerializer.Deserialize<AppPreferences>(File.ReadAllText(FilePath), JsonOptions) ?? new AppPreferences();
        }
        catch
        {
            return new AppPreferences();
        }
    }

    public void Save(AppPreferences preferences) => WorkspaceStore.AtomicWrite(FilePath, JsonSerializer.Serialize(preferences, JsonOptions));
}
