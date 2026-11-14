namespace Notein_win.Models;

public sealed class WorkspaceSettings
{
    public int Version { get; set; } = 3;
    public PagePaneMode PagePaneMode { get; set; } = PagePaneMode.CurrentSection;
    public bool SectionPaneVisible { get; set; } = true;
    public bool PagePaneVisible { get; set; } = true;
    public bool ZoomLocked { get; set; }
    public bool PredictionEnabled { get; set; } = true;
    public string ToolShelfJson { get; set; } = string.Empty;
    public string PressureEraserToolId { get; set; } = "eraser-stroke";
    public double PanoramaSensitivity { get; set; } = 1d;
    public List<string> QuickToolbarIds { get; set; } = FullPageToolbarConfig.DefaultIds.ToList();
    public ImageImportDisplayMode ImageDisplayMode { get; set; } = ImageImportDisplayMode.Invert;
    public ImageImportDisplayMode PdfDisplayMode { get; set; } = ImageImportDisplayMode.Invert;
    public int DarkImageImportPolicyVersion { get; set; }
    public void UpgradeDarkImageImportPolicy()
    {
        if (DarkImageImportPolicyVersion >= 1) return;
        if (ImageDisplayMode == ImageImportDisplayMode.Ask) ImageDisplayMode = ImageImportDisplayMode.Invert;
        if (PdfDisplayMode == ImageImportDisplayMode.Ask) PdfDisplayMode = ImageImportDisplayMode.Invert;
        DarkImageImportPolicyVersion = 1;
    }
    public PageAppearance LastPageAppearance { get; set; } = new();
    public Dictionary<string, string> ShortcutBindings { get; set; } = ShortcutActionCatalog.DefaultBindings();
    public Dictionary<string, PageViewport> PageViewports { get; set; } = new();
    public WorkspaceNavigation? Navigation { get; set; }
}

public sealed class WorkspaceNavigation
{
    public string ActiveNotebookId { get; set; } = string.Empty;
    public Dictionary<string, NotebookNavigation> Notebooks { get; set; } = new();

    public static WorkspaceNavigation Capture(NotebookLibrary library) => new()
    {
        ActiveNotebookId = library.ActiveNotebookId,
        Notebooks = library.Notebooks.ToDictionary(notebook => notebook.Id, notebook => new NotebookNavigation
        {
            Expanded = notebook.Expanded, FocusedSectionId = notebook.FocusedSectionId,
            SelectedPageId = notebook.SelectedPageId, SelectedPageSectionId = notebook.SelectedPageSectionId,
            ExpandedSections = notebook.FlattenAllSections().ToDictionary(section => section.Id, section => section.Expanded),
        }),
    };

    public void Restore(NotebookLibrary library, PagePaneMode mode)
    {
        library.SelectNotebook(ActiveNotebookId);
        foreach (var notebook in library.Notebooks)
        {
            if (!Notebooks.TryGetValue(notebook.Id, out var local)) continue;
            notebook.Expanded = local.Expanded;
            notebook.FocusedSectionId = local.FocusedSectionId == null || notebook.FindSection(local.FocusedSectionId) != null
                ? local.FocusedSectionId : notebook.FlattenAllSections().FirstOrDefault()?.Id;
            foreach (var section in notebook.FlattenAllSections())
                if (local.ExpandedSections.TryGetValue(section.Id, out var expanded)) section.Expanded = expanded;
            notebook.SelectFirstPage(mode);
            notebook.SelectPage(local.SelectedPageSectionId, local.SelectedPageId, mode);
        }
    }
}

public sealed class NotebookNavigation
{
    public bool Expanded { get; set; } = true;
    public string? FocusedSectionId { get; set; }
    public string SelectedPageId { get; set; } = string.Empty;
    public string SelectedPageSectionId { get; set; } = string.Empty;
    public Dictionary<string, bool> ExpandedSections { get; set; } = new();
}

public sealed class AppPreferences
{
    public int Version { get; set; } = 1;
    public string WorkspaceRoot { get; set; } = string.Empty;
    public double WindowWidth { get; set; } = 1280;
    public double WindowHeight { get; set; } = 820;
    public bool WindowMaximized { get; set; }
    public PageTheme? PageTheme { get; set; }
    public CanvasBackgroundFormat? DefaultPageBackground { get; set; }
    public PrintPreferences LastPrintOptions { get; set; } = new();
}

public sealed class SearchResult
{
    public string NotebookId { get; init; } = string.Empty;
    public string SectionId { get; init; } = string.Empty;
    public string PageId { get; init; } = string.Empty;
    public string PageTitle { get; init; } = string.Empty;
    public string Context { get; init; } = string.Empty;
    public string DisplayText => string.IsNullOrWhiteSpace(Context) ? PageTitle : $"{PageTitle}  ·  {Context}";
}
