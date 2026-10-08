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
        RestoreMissingCurrent(LibraryPath);
        if (!File.Exists(LibraryPath))
        {
            var created = NotebookLibrary.Sample();
            SaveLibrary(created);
            SaveWelcomePage(created.ActiveNotebook);
            return created;
        }

        var library = ReadRecoverable<NotebookLibrary>(LibraryPath, JsonOptions, value => value.Notebooks.Count > 0);
        if (library.Notebooks.All(notebook => notebook.Id != library.ActiveNotebookId))
        {
            library.ActiveNotebookId = library.Notebooks[0].Id;
        }
        NormalizeSelections(library);
        return library;
    }

    public void SaveLibrary(NotebookLibrary library) => AtomicWrite(LibraryPath, JsonSerializer.Serialize(library, JsonOptions));

    public WorkspaceSettings LoadSettings()
    {
        RestoreMissingCurrent(SettingsPath);
        var settings = File.Exists(SettingsPath) ? ReadRecoverable<WorkspaceSettings>(SettingsPath, JsonOptions) : new WorkspaceSettings();
        settings.UpgradeDarkImageImportPolicy();
        return settings;
    }

    public void SaveSettings(WorkspaceSettings settings) => AtomicWrite(SettingsPath, JsonSerializer.Serialize(settings, JsonOptions));

    public PageDocument LoadPage(string notebookId, string pageId)
    {
        var path = GetPageDocumentPath(notebookId, pageId);
        RestoreMissingCurrent(path);
        if (!File.Exists(path)) return new PageDocument(notebookId, pageId);
        var document = ReadRecoverable<PageDocument>(path, PageJsonOptions);
        document.NotebookId = notebookId;
        document.PageId = pageId;
        return document;
    }

    public void SavePage(PageDocument document)
    {
        document.Revision++;
        document.UpdatedAt = DateTime.UtcNow;
        WritePageSnapshot(document);
    }

    public void WritePageSnapshot(PageDocument snapshot) =>
        AtomicWrite(GetPageDocumentPath(snapshot.NotebookId, snapshot.PageId), JsonSerializer.SerializeToUtf8Bytes(snapshot, PageJsonOptions));

    public string GetNotebookDirectory(string notebookId)
    {
        var path = Path.Combine(RootDirectory, "notebooks", SafeId(notebookId));
        Directory.CreateDirectory(path);
        return path;
    }

    public void DeleteNotebookData(string notebookId)
    {
        if (string.IsNullOrWhiteSpace(notebookId) || notebookId is "." or "..")
            throw new ArgumentException("笔记本标识无效。", nameof(notebookId));
        var notebooksRoot = Path.GetFullPath(Path.Combine(RootDirectory, "notebooks"));
        var directory = Path.GetFullPath(Path.Combine(notebooksRoot, SafeId(notebookId)));
        if (!string.Equals(Path.GetDirectoryName(directory), notebooksRoot, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("笔记本目录必须位于工作区内。", nameof(notebookId));
        if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true);
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
        if (File.Exists(documentPath + ".previous")) File.Delete(documentPath + ".previous");
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
        => AtomicWrite(path, System.Text.Encoding.UTF8.GetBytes(content));

    public static void AtomicWrite(string path, ReadOnlySpan<byte> content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + $".{Guid.NewGuid():N}.tmp";
        try
        {
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(content);
                stream.Flush(flushToDisk: true);
            }
            if (File.Exists(path)) File.Replace(temporary, path, path + ".previous");
            else File.Move(temporary, path);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
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

    internal static void RestoreMissingCurrent(string path)
    {
        if (!File.Exists(path) && File.Exists(path + ".previous")) File.Copy(path + ".previous", path);
    }

    internal static T ReadRecoverable<T>(string path, JsonSerializerOptions options, Func<T, bool>? valid = null) where T : class
    {
        T Read(string source)
        {
            using var stream = File.OpenRead(source);
            var result = JsonSerializer.Deserialize<T>(stream, options);
            if (result == null || valid?.Invoke(result) == false) throw new JsonException("笔记数据无效。");
            return result;
        }
        try { return Read(path); }
        catch (JsonException error)
        {
            PreserveCorruptFile(path);
            if (!File.Exists(path + ".previous"))
                throw new InvalidDataException($"无法读取笔记数据，原文件已保留：{path}", error);
            T recovered;
            try { recovered = Read(path + ".previous"); }
            catch (JsonException previousError)
            {
                throw new InvalidDataException($"当前和恢复版本均无效，已停止加载：{path}", previousError);
            }
            File.Copy(path + ".previous", path, overwrite: true);
            return recovered;
        }
    }

    private static void PreserveCorruptFile(string path)
    {
        if (!File.Exists(path)) return;
        var backup = $"{path}.corrupt-{DateTime.UtcNow:yyyyMMdd-HHmmss}-{Guid.NewGuid():N}";
        File.Copy(path, backup, overwrite: false);
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
        WorkspaceStore.RestoreMissingCurrent(FilePath);
        if (!File.Exists(FilePath)) return new AppPreferences();
        return WorkspaceStore.ReadRecoverable<AppPreferences>(FilePath, JsonOptions);
    }

    public void Save(AppPreferences preferences) => WorkspaceStore.AtomicWrite(FilePath, JsonSerializer.Serialize(preferences, JsonOptions));
}
