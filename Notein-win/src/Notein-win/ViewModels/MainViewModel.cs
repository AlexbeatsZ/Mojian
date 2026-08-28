using System.Collections.ObjectModel;
using System.IO;
using System.Windows.Input;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.ViewModels;

public enum CanvasSlot { Left, Right }

public sealed class MainViewModel : ObservableObject
{
    public const double MinimumPanoramaSensitivity = 0.25d;
    public const double MaximumPanoramaSensitivity = 3d;

    private readonly PreferencesStore _preferencesStore;
    private AppPreferences _preferences;
    private WorkspaceStore _store;
    private WorkspaceSettings _settings;
    private NotebookLibrary _library;
    private ToolShelf _toolShelf;
    private FullPageToolbarConfig _fullPageConfig;

    private PageDocument? _primaryDocument;
    private PageDocument? _secondaryDocument;
    private PageAppearance _activePageAppearance = new();
    private CanvasSlot _focusedSlot = CanvasSlot.Left;
    private RibbonTab _activeRibbonTab = RibbonTab.Draw;
    private bool _isFullPageMode;
    private bool _isFullPageSectionPaneOpen;
    private bool _isFullPagePagePaneOpen;
    private bool _isSplitView;
    private bool _isFloatingToolbarVisible = true;
    private bool _isToolFlyoutOpen;
    private bool _singleFingerPanEnabled;
    private bool _insertSpaceModeEnabled;
    private SpringLoadedToolKind? _springLoadedToolKind;
    private ToolPreset? _springLoadedEraserTool;
    private bool _springLoadedRestorePan;
    private bool _springLoadedRestoreInsertSpace;
    private CanvasTagKind? _pendingTagKind;
    private bool _isBackstageOpen;
    private string _backstageTab = "Info";
    private string _searchQuery = string.Empty;
    private string _saveStateText = "已保存";

    public ObservableCollection<INotebookTreeRow> NotebookTreeRows { get; } = new();
    public ObservableCollection<PagePaneRow> PagePaneRows { get; } = new();
    public ObservableCollection<ToolPreset> Tools { get; } = new();
    public ObservableCollection<ToolPreset> LassoTools { get; } = new();
    public ObservableCollection<ToolPreset> EraserTools { get; } = new();
    public ObservableCollection<ToolPreset> HighlighterTools { get; } = new();
    public ObservableCollection<ToolPreset> PenTools { get; } = new();
    public ObservableCollection<SearchResult> SearchResults { get; } = new();
    public ObservableCollection<FullPageToolDescriptor> FullPageToolbarItems { get; } = new();
    public ObservableCollection<FullPageToolDescriptor> FullPageToolbarAvailableItems { get; } = new();
    public ObservableCollection<ShortcutActionGroup> ShortcutGroups { get; } = new();

    public NotebookLibrary Library => _library;
    public NotebookWorkspace ActiveWorkspace => _library.ActiveNotebook;
    public WorkspaceStore Store => _store;
    public string WorkspaceRoot => _store.RootDirectory;
    public ToolShelf ToolShelf => _toolShelf;
    public FullPageToolbarConfig FullPageConfig => _fullPageConfig;
    public ToolPreset SelectedTool => _springLoadedEraserTool ?? _toolShelf.Selected;
    public ToolPreset PressureEraserTool =>
        _toolShelf.Tools.FirstOrDefault(tool =>
            tool.Kind == DrawingToolKind.Eraser &&
            tool.Id == _settings.PressureEraserToolId) ??
        _toolShelf.Tools.First(tool => tool.Kind == DrawingToolKind.Eraser);
    public bool SelectedToolIsInk => SelectedTool.Kind is DrawingToolKind.Pen or DrawingToolKind.Highlighter;
    public bool SelectedToolIsPen => SelectedTool.Kind == DrawingToolKind.Pen;
    public bool SelectedToolIsHighlighter => SelectedTool.Kind == DrawingToolKind.Highlighter;
    public bool SelectedToolIsEraser => SelectedTool.Kind == DrawingToolKind.Eraser;
    public bool SelectedToolIsLasso => SelectedTool.Kind == DrawingToolKind.Lasso;
    public bool SelectedLassoIsFreeform => SelectedToolIsLasso && SelectedTool.LassoShape == LassoShape.Freeform;
    public bool SelectedLassoIsRectangle => SelectedToolIsLasso && SelectedTool.LassoShape == LassoShape.Rectangle;
    public PageAppearance LastPageAppearance => _settings.LastPageAppearance.Clone();

    public PageDocument? PrimaryDocument
    {
        get => _primaryDocument;
        private set
        {
            if (!SetField(ref _primaryDocument, value)) return;
            OnPropertyChanged(nameof(PrimaryPageAppearance));
            OnPropertyChanged(nameof(PrimaryPageTitle));
        }
    }

    public PageDocument? SecondaryDocument
    {
        get => _secondaryDocument;
        private set
        {
            if (!SetField(ref _secondaryDocument, value)) return;
            OnPropertyChanged(nameof(SecondaryPageAppearance));
            OnPropertyChanged(nameof(SecondaryPageTitle));
        }
    }

    public PageDocument? FocusedDocument => FocusedSlot == CanvasSlot.Right ? SecondaryDocument : PrimaryDocument;
    public PageAppearance PrimaryPageAppearance => AppearanceForDocument(PrimaryDocument);
    public PageAppearance SecondaryPageAppearance => AppearanceForDocument(SecondaryDocument);
    public string PrimaryPageTitle => TitleForDocument(PrimaryDocument);
    public string SecondaryPageTitle => TitleForDocument(SecondaryDocument);

    public PageAppearance ActivePageAppearance
    {
        get => _activePageAppearance;
        private set => SetField(ref _activePageAppearance, value);
    }

    public bool IsPageTitleHidden => !ActivePageAppearance.ShowTitle;
    public bool IsDarkPageBackground => ActivePageAppearance.Theme == PageTheme.Dark;

    public CanvasSlot FocusedSlot
    {
        get => _focusedSlot;
        private set
        {
            if (SetField(ref _focusedSlot, value))
            {
                OnPropertyChanged(nameof(IsLeftCanvasFocused));
                OnPropertyChanged(nameof(IsRightCanvasFocused));
                OnPropertyChanged(nameof(FocusedDocument));
            }
        }
    }

    public bool IsLeftCanvasFocused => FocusedSlot == CanvasSlot.Left;
    public bool IsRightCanvasFocused => FocusedSlot == CanvasSlot.Right;

    public string ActiveNotebookTitle => ActiveWorkspace.Title;
    public string ActiveSectionTitle => ActiveWorkspace.SelectedSection()?.Title ?? "笔记本根目录";
    public string ActivePageTitle
    {
        get => ActiveWorkspace.SelectedPage()?.Title ?? "未选择页面";
        set
        {
            var page = ActiveWorkspace.SelectedPage();
            var normalized = value?.Trim();
            if (page == null || string.IsNullOrWhiteSpace(normalized) || page.Title == normalized) return;
            page.Title = normalized;
            page.UpdatedAt = DateTime.UtcNow;
            SaveLibrary();
            RefreshNavigation();
            OnPropertyChanged();
        }
    }

    public string BreadcrumbText => string.Join("  /  ", ActiveWorkspace.Breadcrumb().Select(section => section.Title));
    public string SaveStateText { get => _saveStateText; private set => SetField(ref _saveStateText, value); }
    public bool HasActivePage => FocusedDocument != null;

    public bool IsSectionPaneVisible
    {
        get => _settings.SectionPaneVisible;
        set
        {
            if (_settings.SectionPaneVisible == value) return;
            _settings.SectionPaneVisible = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsSectionPaneOpen));
            SaveSettings();
        }
    }

    public bool IsSectionPaneOpen
    {
        get => IsFullPageMode ? _isFullPageSectionPaneOpen : IsSectionPaneVisible;
        set
        {
            if (!IsFullPageMode)
            {
                IsSectionPaneVisible = value;
                return;
            }

            SetField(ref _isFullPageSectionPaneOpen, value);
        }
    }

    public bool IsPagePaneVisible
    {
        get => _settings.PagePaneVisible;
        set
        {
            if (_settings.PagePaneVisible == value) return;
            _settings.PagePaneVisible = value;
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsPagePaneOpen));
            SaveSettings();
        }
    }

    public bool IsPagePaneOpen
    {
        get => IsFullPageMode ? _isFullPagePagePaneOpen : IsPagePaneVisible;
        set
        {
            if (!IsFullPageMode)
            {
                IsPagePaneVisible = value;
                return;
            }

            SetField(ref _isFullPagePagePaneOpen, value);
        }
    }

    public bool ZoomLocked
    {
        get => _settings.ZoomLocked;
        set
        {
            if (_settings.ZoomLocked == value) return;
            _settings.ZoomLocked = value;
            OnPropertyChanged();
            SaveSettings();
        }
    }

    public bool PredictionEnabled
    {
        get => _settings.PredictionEnabled;
        set
        {
            if (_settings.PredictionEnabled == value) return;
            _settings.PredictionEnabled = value;
            OnPropertyChanged();
            SaveSettings();
        }
    }

    public ImageImportDisplayMode ImageDisplayMode
    {
        get => _settings.ImageDisplayMode;
        set
        {
            if (_settings.ImageDisplayMode == value) return;
            _settings.ImageDisplayMode = value;
            OnPropertyChanged();
            SaveSettings();
        }
    }

    public ImageImportDisplayMode PdfDisplayMode
    {
        get => _settings.PdfDisplayMode;
        set
        {
            if (_settings.PdfDisplayMode == value) return;
            _settings.PdfDisplayMode = value;
            OnPropertyChanged();
            SaveSettings();
        }
    }

    public PagePaneMode PagePaneMode
    {
        get => _settings.PagePaneMode;
        set
        {
            if (_settings.PagePaneMode == value) return;
            SaveFocusedDocument();
            _settings.PagePaneMode = value;
            ActiveWorkspace.SelectFirstPage(value);
            LoadSelectedIntoFocusedSlot();
            OnPropertyChanged();
            OnPropertyChanged(nameof(PagePaneModeButtonText));
            SaveSettings();
            SaveLibrary();
            RefreshNavigation();
        }
    }

    public string PredictionButtonText => PredictionEnabled ? "开启" : "关闭";
    public string PagePaneModeButtonText => PagePaneMode == PagePaneMode.CurrentSection ? "本层" : "递归";
    public string ImageDisplayModeButtonText => ImageDisplayMode switch
    {
        ImageImportDisplayMode.Invert => "自动反色",
        ImageImportDisplayMode.Original => "保持原图",
        _ => "导入时询问"
    };
    public string PdfDisplayModeButtonText => PdfDisplayMode switch
    {
        ImageImportDisplayMode.Invert => "自动反色",
        ImageImportDisplayMode.Original => "保持原图",
        _ => "导入时询问"
    };

    public ICommand TogglePredictionCommand => new RelayCommand(() =>
    {
        PredictionEnabled = !PredictionEnabled;
        OnPropertyChanged(nameof(PredictionButtonText));
    });

    public ICommand TogglePagePaneModeCommand => new RelayCommand(() =>
    {
        PagePaneMode = PagePaneMode == PagePaneMode.CurrentSection ? PagePaneMode.Recursive : PagePaneMode.CurrentSection;
        OnPropertyChanged(nameof(PagePaneModeButtonText));
    });

    public ICommand CycleImageDisplayModeCommand => new RelayCommand(() =>
    {
        ImageDisplayMode = (ImageImportDisplayMode)(((int)ImageDisplayMode + 1) % 3);
        OnPropertyChanged(nameof(ImageDisplayModeButtonText));
    });

    public ICommand CyclePdfDisplayModeCommand => new RelayCommand(() =>
    {
        PdfDisplayMode = (ImageImportDisplayMode)(((int)PdfDisplayMode + 1) % 3);
        OnPropertyChanged(nameof(PdfDisplayModeButtonText));
    });

    public bool CanAddPage => ActiveWorkspace.HasSelectedSection();

    public RibbonTab ActiveRibbonTab
    {
        get => _activeRibbonTab;
        set
        {
            if (!SetField(ref _activeRibbonTab, value)) return;
            OnPropertyChanged(nameof(IsHomeRibbonVisible));
            OnPropertyChanged(nameof(IsInsertRibbonVisible));
            OnPropertyChanged(nameof(IsDrawRibbonVisible));
            OnPropertyChanged(nameof(IsViewRibbonVisible));
        }
    }

    public bool IsHomeRibbonVisible => ActiveRibbonTab == RibbonTab.Home;
    public bool IsInsertRibbonVisible => ActiveRibbonTab == RibbonTab.Insert;
    public bool IsDrawRibbonVisible => ActiveRibbonTab == RibbonTab.Draw;
    public bool IsViewRibbonVisible => ActiveRibbonTab == RibbonTab.View;

    public bool IsFullPageMode
    {
        get => _isFullPageMode;
        set
        {
            if (!SetField(ref _isFullPageMode, value)) return;
            if (value)
            {
                _isFullPageSectionPaneOpen = false;
                _isFullPagePagePaneOpen = false;
            }
            OnPropertyChanged(nameof(IsNormalChromeVisible));
            OnPropertyChanged(nameof(IsSectionPaneOpen));
            OnPropertyChanged(nameof(IsPagePaneOpen));
        }
    }

    public bool IsNormalChromeVisible => !IsFullPageMode;

    public bool IsSplitView
    {
        get => _isSplitView;
        private set => SetField(ref _isSplitView, value);
    }

    public bool IsFloatingToolbarVisible { get => _isFloatingToolbarVisible; set => SetField(ref _isFloatingToolbarVisible, value); }
    public bool IsToolFlyoutOpen { get => _isToolFlyoutOpen; set => SetField(ref _isToolFlyoutOpen, value); }

    public bool SingleFingerPanEnabled
    {
        get => _singleFingerPanEnabled;
        set
        {
            if (!SetField(ref _singleFingerPanEnabled, value)) return;
            if (value) InsertSpaceModeEnabled = false;
            RefreshTools();
        }
    }

    public bool InsertSpaceModeEnabled
    {
        get => _insertSpaceModeEnabled;
        set
        {
            if (!SetField(ref _insertSpaceModeEnabled, value)) return;
            if (value) SingleFingerPanEnabled = false;
            RefreshTools();
        }
    }

    public double PanoramaSensitivity
    {
        get => Math.Clamp(_settings.PanoramaSensitivity, MinimumPanoramaSensitivity, MaximumPanoramaSensitivity);
        set
        {
            var normalized = Math.Clamp(value, MinimumPanoramaSensitivity, MaximumPanoramaSensitivity);
            if (Math.Abs(_settings.PanoramaSensitivity - normalized) < 0.0001d) return;
            _settings.PanoramaSensitivity = normalized;
            OnPropertyChanged();
            SaveSettings();
        }
    }

    public CanvasTagKind? PendingTagKind { get => _pendingTagKind; set => SetField(ref _pendingTagKind, value); }
    public bool IsBackstageOpen { get => _isBackstageOpen; set => SetField(ref _isBackstageOpen, value); }

    public string BackstageTab
    {
        get => _backstageTab;
        set
        {
            if (!SetField(ref _backstageTab, value)) return;
            OnPropertyChanged(nameof(IsBackstageInfo));
            OnPropertyChanged(nameof(IsBackstageNew));
            OnPropertyChanged(nameof(IsBackstageOpenPage));
            OnPropertyChanged(nameof(IsBackstageSaveCopy));
            OnPropertyChanged(nameof(IsBackstagePrint));
            OnPropertyChanged(nameof(IsBackstageShare));
            OnPropertyChanged(nameof(IsBackstageExport));
            OnPropertyChanged(nameof(IsBackstageToolbar));
            OnPropertyChanged(nameof(IsBackstageAccount));
            OnPropertyChanged(nameof(IsBackstageOptions));
            OnPropertyChanged(nameof(IsBackstageShortcuts));
        }
    }

    public bool IsBackstageInfo => BackstageTab == "Info";
    public bool IsBackstageNew => BackstageTab == "New";
    public bool IsBackstageOpenPage => BackstageTab == "Open";
    public bool IsBackstageSaveCopy => BackstageTab == "SaveCopy";
    public bool IsBackstagePrint => BackstageTab == "Print";
    public bool IsBackstageShare => BackstageTab == "Share";
    public bool IsBackstageExport => BackstageTab == "Export";
    public bool IsBackstageToolbar => BackstageTab == "Toolbar";
    public bool IsBackstageAccount => BackstageTab == "Account";
    public bool IsBackstageOptions => BackstageTab == "Options";
    public bool IsBackstageShortcuts => BackstageTab == "Shortcuts";

    public string SearchQuery
    {
        get => _searchQuery;
        set
        {
            if (!SetField(ref _searchQuery, value)) return;
            RunSearch(value);
            OnPropertyChanged(nameof(IsSearchOpen));
        }
    }

    public bool IsSearchOpen => SearchResults.Count > 0;

    public event Action? RequestUndo;
    public event Action? RequestRedo;
    public event Action? RequestClearPage;
    public event Action<float?>? RequestZoom;
    public event Action? RequestFitPageWidth;
    public event Action? RequestAppearanceRefresh;

    public ICommand ToggleSectionPaneCommand { get; }
    public ICommand TogglePagePaneCommand { get; }
    public ICommand ToggleFullPageCommand { get; }
    public ICommand ToggleFloatingToolbarCommand { get; }
    public ICommand ToggleSplitViewCommand { get; }
    public ICommand SelectNotebookCommand { get; }
    public ICommand ToggleNotebookExpandCommand { get; }
    public ICommand AddNotebookCommand { get; }
    public ICommand SelectSectionCommand { get; }
    public ICommand FocusNotebookRootCommand { get; }
    public ICommand AddSectionCommand { get; }
    public ICommand ToggleSectionExpandCommand { get; }
    public ICommand SelectPageCommand { get; }
    public ICommand AddPageCommand { get; }
    public ICommand SetPagePaneModeCommand { get; }
    public ICommand SelectRibbonTabCommand { get; }
    public ICommand SelectToolCommand { get; }
    public ICommand AddToolCommand { get; }
    public ICommand ArmTagPlacementCommand { get; }
    public ICommand SetBackgroundFormatCommand { get; }
    public ICommand TogglePageThemeCommand { get; }
    public ICommand SetPaperKindCommand { get; }
    public ICommand SetImageDisplayModeCommand { get; }
    public ICommand SetPdfDisplayModeCommand { get; }
    public ICommand OpenBackstageCommand { get; }
    public ICommand CloseBackstageCommand { get; }
    public ICommand SelectBackstageTabCommand { get; }
    public ICommand UndoCommand { get; }
    public ICommand RedoCommand { get; }
    public ICommand ClearPageCommand { get; }
    public ICommand ZoomCommand { get; }
    public ICommand FitPageWidthCommand { get; }
    public ICommand NavigateSearchResultCommand { get; }
    public ICommand ToggleFullPageToolCommand { get; }

    public MainViewModel() : this(null, null) { }

    public MainViewModel(string? workspaceRoot, PreferencesStore? preferencesStore)
    {
        _preferencesStore = preferencesStore ?? new PreferencesStore();
        _preferences = _preferencesStore.Load();
        var root = workspaceRoot ?? (string.IsNullOrWhiteSpace(_preferences.WorkspaceRoot) ? WorkspaceStore.DefaultRoot : _preferences.WorkspaceRoot);
        _store = new WorkspaceStore(root);
        _library = _store.LoadLibrary();
        _settings = _store.LoadSettings();
        _settings.ShortcutBindings ??= ShortcutActionCatalog.DefaultBindings();
        _toolShelf = ToolShelf.FromJson(_settings.ToolShelfJson) ?? new ToolShelf();
        var settingsMigrated = MigrateWorkspaceSettings();
        _fullPageConfig = new FullPageToolbarConfig(_settings.QuickToolbarIds);
        if (settingsMigrated) SaveSettings();
        _preferences.WorkspaceRoot = _store.RootDirectory;
        _preferencesStore.Save(_preferences);

        ToggleSectionPaneCommand = new RelayCommand(() => IsSectionPaneOpen = !IsSectionPaneOpen);
        TogglePagePaneCommand = new RelayCommand(() => IsPagePaneOpen = !IsPagePaneOpen);
        ToggleFullPageCommand = new RelayCommand(() => IsFullPageMode = !IsFullPageMode);
        ToggleFloatingToolbarCommand = new RelayCommand(() => IsFloatingToolbarVisible = !IsFloatingToolbarVisible);
        ToggleSplitViewCommand = new RelayCommand(ToggleSplitView);
        SelectNotebookCommand = new RelayCommand<string>(SelectNotebook);
        ToggleNotebookExpandCommand = new RelayCommand<string>(ToggleNotebookExpanded);
        AddNotebookCommand = new RelayCommand(() => AddNotebook("新笔记本"));
        SelectSectionCommand = new RelayCommand<string>(SelectSection);
        FocusNotebookRootCommand = new RelayCommand(FocusNotebookRoot);
        AddSectionCommand = new RelayCommand(() => AddSection("新分区"));
        ToggleSectionExpandCommand = new RelayCommand<string>(ToggleSectionExpanded);
        SelectPageCommand = new RelayCommand<PagePaneRow>(SelectPage);
        AddPageCommand = new RelayCommand(() => AddPage("无标题页", _settings.LastPageAppearance), () => CanAddPage);
        SetPagePaneModeCommand = new RelayCommand<PagePaneMode>(mode => PagePaneMode = mode);
        SelectRibbonTabCommand = new RelayCommand<RibbonTab>(SelectRibbonTab);
        SelectToolCommand = new RelayCommand<string>(SelectTool);
        AddToolCommand = new RelayCommand<DrawingToolKind>(AddTool);
        ArmTagPlacementCommand = new RelayCommand<CanvasTagKind>(kind => PendingTagKind = kind);
        SetBackgroundFormatCommand = new RelayCommand<CanvasBackgroundFormat>(SetBackgroundFormat);
        TogglePageThemeCommand = new RelayCommand(TogglePageTheme);
        SetPaperKindCommand = new RelayCommand<PaperKind>(SetPaperKind);
        SetImageDisplayModeCommand = new RelayCommand<ImageImportDisplayMode>(mode => ImageDisplayMode = mode);
        SetPdfDisplayModeCommand = new RelayCommand<ImageImportDisplayMode>(mode => PdfDisplayMode = mode);
        OpenBackstageCommand = new RelayCommand(() => IsBackstageOpen = true);
        CloseBackstageCommand = new RelayCommand(() => IsBackstageOpen = false);
        SelectBackstageTabCommand = new RelayCommand<string>(tab => BackstageTab = tab ?? "Info");
        UndoCommand = new RelayCommand(() => RequestUndo?.Invoke(), () => HasActivePage);
        RedoCommand = new RelayCommand(() => RequestRedo?.Invoke(), () => HasActivePage);
        ClearPageCommand = new RelayCommand(() => RequestClearPage?.Invoke(), () => HasActivePage);
        ZoomCommand = new RelayCommand<float>(scale => RequestZoom?.Invoke(scale));
        FitPageWidthCommand = new RelayCommand(() => RequestFitPageWidth?.Invoke());
        NavigateSearchResultCommand = new RelayCommand<SearchResult>(NavigateToSearchResult);
        ToggleFullPageToolCommand = new RelayCommand<string>(ToggleFullPageTool);

        LoadSelectedIntoFocusedSlot();
        RefreshNavigation();
        RefreshTools();
    }

    public void AddNotebook(string title)
    {
        SaveFocusedDocument();
        var notebook = _library.AddNotebook(title);
        notebook.AddSection("未归档");
        notebook.AddPage("无标题页", _settings.LastPageAppearance);
        SaveLibrary();
        LoadSelectedIntoFocusedSlot();
        RefreshNavigation();
    }

    public void AddSection(string title)
    {
        SaveFocusedDocument();
        ActiveWorkspace.AddSectionAtFocus(title);
        SaveLibrary();
        LoadSelectedIntoFocusedSlot();
        RefreshNavigation();
    }

    public NotePage? AddPage(string title, PageAppearance appearance)
    {
        if (!CanAddPage) return null;
        SaveFocusedDocument();
        var page = ActiveWorkspace.AddPage(title, appearance);
        _settings.LastPageAppearance = appearance.Clone();
        var document = new PageDocument(ActiveWorkspace.Id, page.Id);
        _store.SavePage(document);
        AssignFocusedDocument(document);
        SaveLibrary();
        SaveSettings();
        RefreshNavigation();
        return page;
    }

    public PageDocument? AddPdfPage(string title, RenderedPdfPage sourcePage, string sourcePath, bool invert)
    {
        var rememberedPageAppearance = _settings.LastPageAppearance.Clone();
        var appearance = new PageAppearance
        {
            PaperKind = PaperKind.Pdf,
            Theme = PageTheme.Dark,
            Background = CanvasBackgroundFormat.Blank,
            PageColor = "#1B1B1B",
            ShowTitle = false,
            SheetCount = 1,
            Width = sourcePage.WidthPoints * 96d / 72d,
            Height = sourcePage.HeightPoints * 96d / 72d,
        };
        var page = AddPage(title, appearance);
        _settings.LastPageAppearance = rememberedPageAppearance;
        SaveSettings();
        var document = FocusedDocument;
        if (page == null || document == null) return null;

        page.IsPdfPage = true;
        page.PdfPageNumber = sourcePage.PageNumber;
        page.PdfPageCount = sourcePage.PageCount;
        page.PdfWidthPoints = sourcePage.WidthPoints;
        page.PdfHeightPoints = sourcePage.HeightPoints;
        var sourceAsset = _store.ImportAsset(document.NotebookId, document.PageId, sourcePath);
        page.PdfSourceAssetName = sourceAsset;
        page.PdfSourceDocumentName = Path.GetFileName(sourcePath);
        var renderedAsset = _store.WriteAsset(document.NotebookId, document.PageId, ".png", sourcePage.PngBytes);
        document.Objects.Add(new PageObject
        {
            Kind = PageObjectKind.PdfPrintout,
            X = 0,
            Y = 0,
            Width = (float)appearance.Width,
            Height = (float)appearance.Height,
            DisplayName = $"{Path.GetFileName(sourcePath)} · {sourcePage.PageNumber}/{sourcePage.PageCount}",
            AssetName = renderedAsset,
            MimeType = "image/png",
            ViewInverted = invert,
            SourceAssetName = sourceAsset,
            SourcePageNumber = sourcePage.PageNumber,
            IsPageBackground = true,
        });
        _store.SavePage(document);
        SaveLibrary();
        RefreshNavigation();
        return document;
    }

    public bool RenameSection(string sectionId, string title)
    {
        if (!ActiveWorkspace.RenameSection(sectionId, title)) return false;
        FinishNavigationMutation();
        return true;
    }

    public bool DeleteSection(string sectionId)
    {
        SaveFocusedDocument();
        var section = ActiveWorkspace.FindSection(sectionId);
        if (section == null) return false;
        var pageIds = PagesInTree(section).Select(page => page.Id).ToList();
        if (ActiveWorkspace.DeleteSection(sectionId) == null) return false;
        foreach (var pageId in pageIds) _store.DeletePageData(ActiveWorkspace.Id, pageId);
        FinishNavigationMutation();
        return true;
    }

    public bool MoveSection(string sectionId, string? targetParentId)
    {
        SaveFocusedDocument();
        if (!ActiveWorkspace.MoveSectionToParent(sectionId, targetParentId)) return false;
        FinishNavigationMutation();
        return true;
    }

    public bool CopySection(string sectionId, string? targetParentId)
    {
        SaveFocusedDocument();
        if (ActiveWorkspace.CopySectionToParent(sectionId, targetParentId, out var pageIds) == null) return false;
        foreach (var (sourcePageId, targetPageId) in pageIds)
            _store.CopyPageData(ActiveWorkspace.Id, sourcePageId, ActiveWorkspace.Id, targetPageId);
        FinishNavigationMutation();
        return true;
    }

    public bool MergeSection(string sectionId, string targetSectionId)
    {
        SaveFocusedDocument();
        if (!ActiveWorkspace.MergeSectionInto(sectionId, targetSectionId)) return false;
        FinishNavigationMutation();
        return true;
    }

    public void AddChildSection(string parentSectionId, string title)
    {
        SaveFocusedDocument();
        ActiveWorkspace.AddSection(title, parentSectionId);
        FinishNavigationMutation();
    }

    public bool RenamePage(string sectionId, string pageId, string title)
    {
        if (!ActiveWorkspace.RenamePage(sectionId, pageId, title)) return false;
        FinishNavigationMutation();
        return true;
    }

    public bool DeletePage(string sectionId, string pageId)
    {
        SaveFocusedDocument();
        if (ActiveWorkspace.DeletePage(sectionId, pageId) == null) return false;
        _store.DeletePageData(ActiveWorkspace.Id, pageId);
        FinishNavigationMutation();
        return true;
    }

    public bool MovePage(string sourceSectionId, string pageId, string targetSectionId, int? targetIndex = null)
    {
        SaveFocusedDocument();
        if (!ActiveWorkspace.MovePage(sourceSectionId, pageId, targetSectionId, targetIndex)) return false;
        FinishNavigationMutation();
        return true;
    }

    public bool MovePageAdjacent(string sourceSectionId, string pageId, string targetSectionId, string targetPageId, bool after)
    {
        SaveFocusedDocument();
        if (!ActiveWorkspace.MovePageAdjacent(sourceSectionId, pageId, targetSectionId, targetPageId, after)) return false;
        FinishNavigationMutation();
        return true;
    }

    public NotePage? CopyPage(string sourceSectionId, string pageId, string targetSectionId, int? targetIndex = null)
    {
        SaveFocusedDocument();
        var copy = ActiveWorkspace.CopyPage(sourceSectionId, pageId, targetSectionId, targetIndex);
        if (copy == null) return null;
        _store.CopyPageData(ActiveWorkspace.Id, pageId, ActiveWorkspace.Id, copy.Id);
        FinishNavigationMutation();
        return copy;
    }

    public void FocusCanvas(CanvasSlot slot)
    {
        if (slot == CanvasSlot.Right && (!IsSplitView || SecondaryDocument == null)) return;
        SaveFocusedDocument();
        FocusedSlot = slot;
        var doc = FocusedDocument;
        if (doc != null) SelectLibraryLocation(doc.NotebookId, doc.PageId);
        RefreshNavigation();
        RefreshPageAppearance();
    }

    private void FinishNavigationMutation()
    {
        SaveLibrary();
        LoadSelectedIntoFocusedSlot();
        RefreshNavigation();
        NotifyAllWorkspaceProperties();
    }

    private static IEnumerable<NotePage> PagesInTree(SectionNode section)
    {
        foreach (var page in section.Pages) yield return page;
        foreach (var child in section.Children)
            foreach (var page in PagesInTree(child))
                yield return page;
    }

    public void SaveDocument(PageDocument? document)
    {
        if (document == null || string.IsNullOrWhiteSpace(document.PageId)) return;
        SaveStateText = "正在保存…";
        _store.SavePage(document);
        var notebook = _library.Notebooks.FirstOrDefault(item => item.Id == document.NotebookId);
        var page = notebook?.FindPage(document.PageId, out _);
        if (page != null) page.UpdatedAt = document.UpdatedAt;
        _store.SaveLibrary(_library);
        SaveStateText = "已保存";
        RefreshPagePaneRows();
    }

    public void SavePageMetadata() => SaveLibrary();

    public void SaveAll()
    {
        var documents = new[] { PrimaryDocument, SecondaryDocument }
            .Where(document => document != null)
            .GroupBy(document => $"{document!.NotebookId}/{document.PageId}")
            .Select(group => group.First());
        foreach (var document in documents) SaveDocument(document);
        SaveSettings();
        SaveLibrary();
        _preferences.WorkspaceRoot = _store.RootDirectory;
        _preferencesStore.Save(_preferences);
    }

    public void SaveToolSettings()
    {
        if (SelectedToolIsEraser) _settings.PressureEraserToolId = SelectedTool.Id;
        SaveSettings();
        OnPropertyChanged(nameof(SelectedTool));
        OnPropertyChanged(nameof(SelectedToolIsInk));
        OnPropertyChanged(nameof(SelectedToolIsPen));
        OnPropertyChanged(nameof(SelectedToolIsHighlighter));
        OnPropertyChanged(nameof(SelectedToolIsEraser));
        OnPropertyChanged(nameof(SelectedToolIsLasso));
        OnPropertyChanged(nameof(SelectedLassoIsFreeform));
        OnPropertyChanged(nameof(SelectedLassoIsRectangle));
    }

    public void SwitchWorkspace(string rootDirectory)
    {
        SaveAll();
        _store = new WorkspaceStore(rootDirectory);
        _library = _store.LoadLibrary();
        _settings = _store.LoadSettings();
        _settings.ShortcutBindings ??= ShortcutActionCatalog.DefaultBindings();
        _toolShelf = ToolShelf.FromJson(_settings.ToolShelfJson) ?? new ToolShelf();
        var settingsMigrated = MigrateWorkspaceSettings();
        _fullPageConfig = new FullPageToolbarConfig(_settings.QuickToolbarIds);
        if (settingsMigrated) SaveSettings();
        _preferences.WorkspaceRoot = _store.RootDirectory;
        _preferencesStore.Save(_preferences);
        FocusedSlot = CanvasSlot.Left;
        IsSplitView = false;
        SecondaryDocument = null;
        LoadSelectedIntoFocusedSlot();
        RefreshNavigation();
        RefreshTools();
        NotifyAllWorkspaceProperties();
    }

    public string ImportAsset(string sourcePath)
    {
        var document = FocusedDocument ?? throw new InvalidOperationException("请先打开一个页面。");
        return _store.ImportAsset(document.NotebookId, document.PageId, sourcePath);
    }

    public string WriteAsset(string extension, ReadOnlySpan<byte> bytes)
    {
        var document = FocusedDocument ?? throw new InvalidOperationException("请先打开一个页面。");
        return _store.WriteAsset(document.NotebookId, document.PageId, extension, bytes);
    }

    public string ResolveAsset(PageDocument document, string assetName) => _store.ResolveAsset(document.NotebookId, document.PageId, assetName);
    public string ActiveAssetDirectory => FocusedDocument == null ? string.Empty : _store.GetAssetDirectory(FocusedDocument.NotebookId, FocusedDocument.PageId);

    public bool NavigateToPage(string notebookId, string pageId)
    {
        SaveFocusedDocument();
        if (!_library.SelectNotebook(notebookId)) return false;
        var notebook = _library.ActiveNotebook;
        var page = notebook.FindPage(pageId, out var owner);
        if (page == null || owner == null) return false;
        notebook.FocusedSectionId = owner.Id;
        notebook.SelectedPageSectionId = owner.Id;
        notebook.SelectedPageId = page.Id;
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
        NotifyAllWorkspaceProperties();
        return true;
    }

    public void SetBackgroundFormat(CanvasBackgroundFormat format)
    {
        var page = ActiveWorkspace.SelectedPage();
        if (page == null) return;
        page.Appearance.Background = format;
        ActivePageAppearance = page.Appearance.Clone();
        SaveLibrary();
        RequestAppearanceRefresh?.Invoke();
    }

    public void SetPaperKind(PaperKind kind)
    {
        var page = ActiveWorkspace.SelectedPage();
        if (page == null) return;
        page.Appearance.PaperKind = kind;
        page.Appearance.SheetCount = Math.Max(1, page.Appearance.SheetCount);
        ActivePageAppearance = page.Appearance.Clone();
        SaveLibrary();
        RequestAppearanceRefresh?.Invoke();
    }

    public void SetPageColor(string color, PageTheme theme)
    {
        var page = ActiveWorkspace.SelectedPage();
        if (page == null) return;
        page.Appearance.PageColor = color;
        page.Appearance.Theme = theme;
        ActivePageAppearance = page.Appearance.Clone();
        SaveLibrary();
        RequestAppearanceRefresh?.Invoke();
    }

    public void TogglePageTitle()
    {
        var page = ActiveWorkspace.SelectedPage();
        if (page == null) return;
        page.Appearance.ShowTitle = !page.Appearance.ShowTitle;
        ActivePageAppearance = page.Appearance.Clone();
        SaveLibrary();
        OnPropertyChanged(nameof(IsPageTitleHidden));
        RequestAppearanceRefresh?.Invoke();
    }

    public void TogglePageTheme()
    {
        var page = ActiveWorkspace.SelectedPage();
        if (page == null) return;
        page.Appearance.Theme = page.Appearance.Theme == PageTheme.Dark ? PageTheme.Light : PageTheme.Dark;
        page.Appearance.PageColor = page.Appearance.Theme == PageTheme.Dark ? "#1B1B1B" : "#FAFAFA";
        ActivePageAppearance = page.Appearance.Clone();
        SaveLibrary();
        OnPropertyChanged(nameof(IsDarkPageBackground));
        RequestAppearanceRefresh?.Invoke();
    }

    private void SelectRibbonTab(RibbonTab tab)
    {
        ActiveRibbonTab = tab;
    }

    private void SelectNotebook(string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        SaveFocusedDocument();
        if (!_library.SelectNotebook(id)) return;
        ActiveWorkspace.FocusNotebook(PagePaneMode);
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
        NotifyAllWorkspaceProperties();
    }

    private void SelectSection(string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        SaveFocusedDocument();
        if (!ActiveWorkspace.SelectSection(id, PagePaneMode)) return;
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
    }

    private void FocusNotebookRoot()
    {
        SaveFocusedDocument();
        ActiveWorkspace.FocusNotebook(PagePaneMode);
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
    }

    private void ToggleSectionExpanded(string? id)
    {
        if (string.IsNullOrWhiteSpace(id) || !ActiveWorkspace.ToggleExpanded(id)) return;
        SaveLibrary();
        RefreshSectionRows();
    }

    private void ToggleNotebookExpanded(string? id)
    {
        var notebook = _library.Notebooks.FirstOrDefault(item => item.Id == id);
        if (notebook == null) return;
        notebook.Expanded = !notebook.Expanded;
        SaveLibrary();
        RefreshSectionRows();
    }

    private void SelectPage(PagePaneRow? row)
    {
        if (row?.Page == null || row.IsHeader) return;
        SaveFocusedDocument();
        if (!ActiveWorkspace.SelectPage(row.SectionId, row.Page.Id, PagePaneMode)) return;
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
    }

    private void SelectTool(string? id)
    {
        SelectToolFromToolbar(id, flyoutWasOpenAtPointerDown: false);
    }

    public void SelectToolFromToolbar(string? id, bool flyoutWasOpenAtPointerDown)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        CancelSpringLoadedTool();
        var wasActiveSelection = _toolShelf.SelectedId == id && !SingleFingerPanEnabled && !InsertSpaceModeEnabled;
        if (!wasActiveSelection && _toolShelf.Select(id))
        {
            if (_toolShelf.Selected.Kind == DrawingToolKind.Eraser)
                _settings.PressureEraserToolId = _toolShelf.Selected.Id;
            IsToolFlyoutOpen = false;
            SingleFingerPanEnabled = false;
            InsertSpaceModeEnabled = false;
            OnPropertyChanged(nameof(SelectedTool));
            NotifySelectedToolProperties();
            SaveSettings();
        }
        else if (wasActiveSelection)
        {
            IsToolFlyoutOpen = !flyoutWasOpenAtPointerDown;
        }
        RefreshTools();
    }

    public bool SelectToolImmediate(string id)
    {
        CancelSpringLoadedTool();
        if (!_toolShelf.Select(id)) return false;
        if (_toolShelf.Selected.Kind == DrawingToolKind.Eraser)
            _settings.PressureEraserToolId = _toolShelf.Selected.Id;
        IsToolFlyoutOpen = false;
        SingleFingerPanEnabled = false;
        InsertSpaceModeEnabled = false;
        RefreshTools();
        SaveSettings();
        return true;
    }

    public bool CycleDrawingTool(int direction)
    {
        var ordered = _toolShelf.Tools
            .OrderBy(tool => tool.Kind switch
            {
                DrawingToolKind.Lasso => 0,
                DrawingToolKind.Eraser => 1,
                DrawingToolKind.Highlighter => 2,
                _ => 3,
            })
            .ToList();
        if (ordered.Count == 0) return false;
        var index = ordered.FindIndex(tool => tool.Id == _toolShelf.SelectedId);
        var next = (Math.Max(0, index) + Math.Sign(direction) + ordered.Count) % ordered.Count;
        return SelectToolImmediate(ordered[next].Id);
    }

    public bool CyclePage(int direction)
    {
        var pages = ActiveWorkspace.GetPagePaneRows(PagePaneMode)
            .Where(row => !row.IsHeader && row.Page != null)
            .ToList();
        if (pages.Count == 0) return false;
        var current = pages.FindIndex(row => row.Page!.Id == ActiveWorkspace.SelectedPageId);
        var next = (Math.Max(0, current) + Math.Sign(direction) + pages.Count) % pages.Count;
        SaveFocusedDocument();
        if (!ActiveWorkspace.SelectPage(pages[next].SectionId, pages[next].Page!.Id, PagePaneMode)) return false;
        LoadSelectedIntoFocusedSlot();
        SaveLibrary();
        RefreshNavigation();
        return true;
    }

    public string? FindShortcutAction(ShortcutGesture gesture)
    {
        if (!gesture.IsValid) return null;
        return _settings.ShortcutBindings.FirstOrDefault(binding =>
            ShortcutGesture.TryParse(binding.Value, out var parsed) && parsed == gesture).Key;
    }

    public void SetShortcut(string actionId, ShortcutGesture gesture)
    {
        if (!gesture.IsValid) return;
        foreach (var conflict in _settings.ShortcutBindings
                     .Where(binding => ShortcutGesture.TryParse(binding.Value, out var parsed) && parsed == gesture)
                     .Select(binding => binding.Key)
                     .ToList())
            _settings.ShortcutBindings.Remove(conflict);
        _settings.ShortcutBindings[actionId] = gesture.Serialize();
        SaveSettings();
        RefreshShortcutCatalog();
    }

    public void ClearShortcut(string actionId)
    {
        if (!_settings.ShortcutBindings.Remove(actionId)) return;
        SaveSettings();
        RefreshShortcutCatalog();
    }

    private void AddTool(DrawingToolKind kind)
    {
        var added = _toolShelf.Add(kind);
        if (kind == DrawingToolKind.Eraser) _settings.PressureEraserToolId = added.Id;
        RefreshTools();
        OnPropertyChanged(nameof(SelectedTool));
        NotifySelectedToolProperties();
        SaveSettings();
    }

    public void SetSelectedToolColor(InkPalette color)
    {
        if (SelectedTool.Kind is not DrawingToolKind.Pen and not DrawingToolKind.Highlighter) return;
        SelectedTool.Color = color;
        SaveSettings();
        RefreshTools();
        OnPropertyChanged(nameof(SelectedTool));
    }

    public void SetSelectedLassoShape(LassoShape shape)
    {
        if (!SelectedToolIsLasso || SelectedTool.LassoShape == shape) return;
        SelectedTool.LassoShape = shape;
        SaveSettings();
        NotifySelectedToolProperties();
    }

    public ToolPreset? BeginSpringLoadedTool(SpringLoadedToolKind kind)
    {
        if (_springLoadedToolKind == kind)
            return kind == SpringLoadedToolKind.Eraser ? _springLoadedEraserTool : null;
        CancelSpringLoadedTool();

        _springLoadedRestorePan = SingleFingerPanEnabled;
        _springLoadedRestoreInsertSpace = InsertSpaceModeEnabled;
        _springLoadedToolKind = kind;
        _springLoadedEraserTool = kind == SpringLoadedToolKind.Eraser ? PressureEraserTool : null;
        _singleFingerPanEnabled = kind == SpringLoadedToolKind.PanoramaHand;
        _insertSpaceModeEnabled = false;
        IsToolFlyoutOpen = false;
        OnPropertyChanged(nameof(SingleFingerPanEnabled));
        OnPropertyChanged(nameof(InsertSpaceModeEnabled));
        RefreshTools();
        return _springLoadedEraserTool;
    }

    public void EndSpringLoadedTool(SpringLoadedToolKind kind)
    {
        if (_springLoadedToolKind != kind) return;
        _springLoadedToolKind = null;
        _springLoadedEraserTool = null;
        _singleFingerPanEnabled = _springLoadedRestorePan;
        _insertSpaceModeEnabled = _springLoadedRestoreInsertSpace;
        OnPropertyChanged(nameof(SingleFingerPanEnabled));
        OnPropertyChanged(nameof(InsertSpaceModeEnabled));
        RefreshTools();
    }

    private void CancelSpringLoadedTool()
    {
        if (_springLoadedToolKind is { } kind) EndSpringLoadedTool(kind);
    }

    private void ToggleSplitView()
    {
        SaveFocusedDocument();
        if (!IsSplitView)
        {
            var current = PrimaryDocument;
            if (current == null) return;
            var pages = ActiveWorkspace.FlattenAllSections().SelectMany(section => section.Pages.Select(page => (section, page))).ToList();
            var index = pages.FindIndex(entry => entry.page.Id == current.PageId);
            var next = pages.Count > 1 ? pages[(Math.Max(0, index) + 1) % pages.Count] : default;
            SecondaryDocument = next.page == null ? _store.LoadPage(current.NotebookId, current.PageId) : _store.LoadPage(ActiveWorkspace.Id, next.page.Id);
            IsSplitView = true;
            FocusedSlot = CanvasSlot.Left;
        }
        else
        {
            if (SecondaryDocument != null)
            {
                SaveDocument(SecondaryDocument);
                PrimaryDocument = SecondaryDocument;
                SelectLibraryLocation(SecondaryDocument.NotebookId, SecondaryDocument.PageId);
            }
            SecondaryDocument = null;
            IsSplitView = false;
            FocusedSlot = CanvasSlot.Left;
            RefreshNavigation();
            RefreshPageAppearance();
        }
    }

    private void NavigateToSearchResult(SearchResult? result)
    {
        if (result == null) return;
        SaveFocusedDocument();
        if (!_library.SelectNotebook(result.NotebookId)) return;
        var workspace = _library.ActiveNotebook;
        workspace.FocusedSectionId = result.SectionId;
        if (!workspace.SelectPage(result.SectionId, result.PageId, PagePaneMode)) return;
        LoadSelectedIntoFocusedSlot();
        SearchQuery = string.Empty;
        SaveLibrary();
        RefreshNavigation();
        NotifyAllWorkspaceProperties();
    }

    private void RunSearch(string query)
    {
        SearchResults.Clear();
        var normalized = query?.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length < 2) return;
        foreach (var notebook in _library.Notebooks)
        {
            foreach (var section in notebook.FlattenAllSections())
            {
                foreach (var page in section.Pages)
                {
                    var context = string.Empty;
                    var titleMatch = page.Title.Contains(normalized, StringComparison.CurrentCultureIgnoreCase);
                    if (!titleMatch)
                    {
                        var document = _store.LoadPage(notebook.Id, page.Id);
                        context = document.TextItems.Select(item => item.Text)
                            .FirstOrDefault(text => text.Contains(normalized, StringComparison.CurrentCultureIgnoreCase)) ?? string.Empty;
                        if (string.IsNullOrWhiteSpace(context)) continue;
                        context = context.ReplaceLineEndings(" ").Trim();
                        if (context.Length > 48) context = context[..48] + "…";
                    }
                    SearchResults.Add(new SearchResult
                    {
                        NotebookId = notebook.Id,
                        SectionId = section.Id,
                        PageId = page.Id,
                        PageTitle = page.Title,
                        Context = context,
                    });
                    if (SearchResults.Count >= 50) return;
                }
            }
        }
    }

    private void LoadSelectedIntoFocusedSlot()
    {
        var page = ActiveWorkspace.SelectedPage();
        var document = page == null ? null : _store.LoadPage(ActiveWorkspace.Id, page.Id);
        AssignFocusedDocument(document);
        RefreshPageAppearance();
        OnPropertyChanged(nameof(HasActivePage));
        CommandManager.InvalidateRequerySuggested();
    }

    private void AssignFocusedDocument(PageDocument? document)
    {
        if (FocusedSlot == CanvasSlot.Right && IsSplitView) SecondaryDocument = document;
        else PrimaryDocument = document;
        OnPropertyChanged(nameof(FocusedDocument));
    }

    private void SaveFocusedDocument() => SaveDocument(FocusedDocument);

    private void SelectLibraryLocation(string notebookId, string pageId)
    {
        if (!_library.SelectNotebook(notebookId)) return;
        var notebook = _library.ActiveNotebook;
        var page = notebook.FindPage(pageId, out var owner);
        if (page == null || owner == null) return;
        notebook.FocusedSectionId = owner.Id;
        notebook.SelectedPageSectionId = owner.Id;
        notebook.SelectedPageId = page.Id;
    }

    private void RefreshPageAppearance()
    {
        ActivePageAppearance = ActiveWorkspace.SelectedPage()?.Appearance.Clone() ?? new PageAppearance();
        OnPropertyChanged(nameof(PrimaryPageAppearance));
        OnPropertyChanged(nameof(SecondaryPageAppearance));
        OnPropertyChanged(nameof(PrimaryPageTitle));
        OnPropertyChanged(nameof(SecondaryPageTitle));
        OnPropertyChanged(nameof(ActivePageTitle));
        OnPropertyChanged(nameof(ActiveSectionTitle));
        OnPropertyChanged(nameof(BreadcrumbText));
        OnPropertyChanged(nameof(IsPageTitleHidden));
        OnPropertyChanged(nameof(IsDarkPageBackground));
    }

    public void RefreshNavigation()
    {
        RefreshSectionRows();
        RefreshPagePaneRows();
        RefreshPageAppearance();
        OnPropertyChanged(nameof(ActiveWorkspace));
        OnPropertyChanged(nameof(ActiveNotebookTitle));
        OnPropertyChanged(nameof(CanAddPage));
        OnPropertyChanged(nameof(PagePaneMode));
    }

    private void RefreshSectionRows()
    {
        NotebookTreeRows.Clear();
        for (var index = 0; index < _library.Notebooks.Count; index++)
        {
            var notebook = _library.Notebooks[index];
            var isActive = notebook.Id == _library.ActiveNotebookId;
            NotebookTreeRows.Add(new NotebookPaneRow(notebook, index, isActive));
            if (isActive && notebook.Expanded)
                foreach (var row in notebook.FlattenSections()) NotebookTreeRows.Add(row);
        }
    }

    private void RefreshPagePaneRows()
    {
        PagePaneRows.Clear();
        foreach (var row in ActiveWorkspace.GetPagePaneRows(PagePaneMode)) PagePaneRows.Add(row);
    }

    private void RefreshTools()
    {
        Tools.Clear();
        LassoTools.Clear();
        EraserTools.Clear();
        HighlighterTools.Clear();
        PenTools.Clear();
        foreach (var tool in _toolShelf.Tools)
        {
            tool.IsSelected = tool.Id == SelectedTool.Id && !SingleFingerPanEnabled && !InsertSpaceModeEnabled;
            switch (tool.Kind)
            {
                case DrawingToolKind.Lasso: LassoTools.Add(tool); break;
                case DrawingToolKind.Eraser: EraserTools.Add(tool); break;
                case DrawingToolKind.Highlighter: HighlighterTools.Add(tool); Tools.Add(tool); break;
                case DrawingToolKind.Pen: PenTools.Add(tool); Tools.Add(tool); break;
            }
        }
        NotifySelectedToolProperties();
        RefreshFullPageToolbarCatalog();
        RefreshShortcutCatalog();
    }

    private void RefreshShortcutCatalog()
    {
        var labels = new Dictionary<ShortcutCategory, string>
        {
            [ShortcutCategory.File] = "文件",
            [ShortcutCategory.Home] = "开始",
            [ShortcutCategory.Insert] = "插入",
            [ShortcutCategory.Draw] = "绘图",
            [ShortcutCategory.View] = "视图",
        };
        var actions = ShortcutActionCatalog.Build(_toolShelf.Tools);
        foreach (var action in actions)
            action.GestureText = _settings.ShortcutBindings.TryGetValue(action.Id, out var value)
                && ShortcutGesture.TryParse(value, out var gesture) ? gesture.DisplayText : "未设置";
        ShortcutGroups.Clear();
        foreach (var category in Enum.GetValues<ShortcutCategory>())
            ShortcutGroups.Add(new ShortcutActionGroup(labels[category], actions.Where(action => action.Category == category).ToList()));
    }

    private void ToggleFullPageTool(string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        if (_fullPageConfig.Contains(id)) _fullPageConfig.Remove(id);
        else _fullPageConfig.Add(id);
        SaveSettings();
        RefreshFullPageToolbarCatalog();
    }

    private void RefreshFullPageToolbarCatalog()
    {
        var catalog = new List<FullPageToolDescriptor>
        {
            new("draw:undo", "撤销", "arrow_undo"),
            new("draw:redo", "重做", "arrow_redo"),
        };
        catalog.AddRange(_toolShelf.Tools.Select(tool => new FullPageToolDescriptor(
            $"tool:{tool.Id}", tool.DisplayName,
            tool.Kind switch { DrawingToolKind.Pen => "pen", DrawingToolKind.Highlighter => "highlight", DrawingToolKind.Eraser => "eraser", _ => "lasso" },
            tool.Kind is DrawingToolKind.Pen or DrawingToolKind.Highlighter ? tool.DisplayColorHex : "#F4F4F4",
            IsSelected: tool.Id == SelectedTool.Id && !SingleFingerPanEnabled && !InsertSpaceModeEnabled)));
        catalog.AddRange(new[]
        {
            new FullPageToolDescriptor("draw:add", "添加绘图工具", "add"),
            new FullPageToolDescriptor("draw:pan", "触控笔抓手", "hand_left", IsSelected: SingleFingerPanEnabled),
            new FullPageToolDescriptor("draw:insert_space", "插入空白空间", "insert_space", PreserveColors: true, IsSelected: InsertSpaceModeEnabled),
            new FullPageToolDescriptor("draw:rule_lines", "设置背景格式", "rule_lines", PreserveColors: true),
            new FullPageToolDescriptor("draw:clear", "清纸", "delete_dismiss"),
        });
        catalog.AddRange(Enum.GetValues<HomeCommand>().Select(command => new FullPageToolDescriptor(
            $"home:{command.GetId()}", command.GetLabel(), command.GetIconKey(),
            command is HomeCommand.Tag or HomeCommand.FindTags ? "#E83139" : "#F4F4F4")));

        FullPageToolbarItems.Clear();
        foreach (var id in _fullPageConfig.Ids)
            if (catalog.FirstOrDefault(item => item.Id == id) is { } descriptor) FullPageToolbarItems.Add(descriptor);
        FullPageToolbarAvailableItems.Clear();
        foreach (var descriptor in catalog.Where(item => !_fullPageConfig.Contains(item.Id))) FullPageToolbarAvailableItems.Add(descriptor);
    }

    private void NotifySelectedToolProperties()
    {
        OnPropertyChanged(nameof(SelectedTool));
        OnPropertyChanged(nameof(SelectedToolIsInk));
        OnPropertyChanged(nameof(SelectedToolIsPen));
        OnPropertyChanged(nameof(SelectedToolIsHighlighter));
        OnPropertyChanged(nameof(SelectedToolIsEraser));
        OnPropertyChanged(nameof(SelectedToolIsLasso));
        OnPropertyChanged(nameof(SelectedLassoIsFreeform));
        OnPropertyChanged(nameof(SelectedLassoIsRectangle));
    }

    private void SaveLibrary() => _store.SaveLibrary(_library);

    private bool MigrateWorkspaceSettings()
    {
        var changed = false;
        var eraser = _toolShelf.Tools.First(tool => tool.Kind == DrawingToolKind.Eraser);
        if (_settings.Version < 3)
        {
            var legacySource = _toolShelf.Tools
                .Where(tool => tool.Kind is DrawingToolKind.Pen or DrawingToolKind.Highlighter)
                .OrderByDescending(tool => tool.Id == _toolShelf.SelectedId)
                .FirstOrDefault(tool => tool.PressureEraseEnabled);
            if (legacySource != null)
            {
                eraser.PressureEraseEnabled = true;
                eraser.PressureEraseThresholdPercent = legacySource.PressureEraseThresholdPercent;
                eraser.PressureEraseDwellMilliseconds = legacySource.PressureEraseDwellMilliseconds;
            }
            _settings.Version = 3;
            changed = true;
        }
        if (_toolShelf.Tools.All(tool => tool.Id != _settings.PressureEraserToolId || tool.Kind != DrawingToolKind.Eraser))
        {
            _settings.PressureEraserToolId = eraser.Id;
            changed = true;
        }
        var sensitivity = Math.Clamp(_settings.PanoramaSensitivity, MinimumPanoramaSensitivity, MaximumPanoramaSensitivity);
        if (Math.Abs(sensitivity - _settings.PanoramaSensitivity) > 0.0001d)
        {
            _settings.PanoramaSensitivity = sensitivity;
            changed = true;
        }
        return changed;
    }

    private void SaveSettings()
    {
        _settings.ToolShelfJson = _toolShelf.ToJson();
        _settings.QuickToolbarIds = _fullPageConfig.Ids.ToList();
        _store.SaveSettings(_settings);
    }

    private void NotifyAllWorkspaceProperties()
    {
        OnPropertyChanged(nameof(Library));
        OnPropertyChanged(nameof(WorkspaceRoot));
        OnPropertyChanged(nameof(ActiveWorkspace));
        OnPropertyChanged(nameof(ActiveNotebookTitle));
        OnPropertyChanged(nameof(ActiveSectionTitle));
        OnPropertyChanged(nameof(ActivePageTitle));
        OnPropertyChanged(nameof(BreadcrumbText));
        OnPropertyChanged(nameof(PagePaneMode));
        OnPropertyChanged(nameof(IsSectionPaneVisible));
        OnPropertyChanged(nameof(IsPagePaneVisible));
        OnPropertyChanged(nameof(IsSectionPaneOpen));
        OnPropertyChanged(nameof(IsPagePaneOpen));
        OnPropertyChanged(nameof(ZoomLocked));
        OnPropertyChanged(nameof(PredictionEnabled));
        OnPropertyChanged(nameof(ImageDisplayMode));
        OnPropertyChanged(nameof(PdfDisplayMode));
        OnPropertyChanged(nameof(PanoramaSensitivity));
        OnPropertyChanged(nameof(SelectedTool));
        OnPropertyChanged(nameof(PrimaryPageAppearance));
        OnPropertyChanged(nameof(SecondaryPageAppearance));
        OnPropertyChanged(nameof(PrimaryPageTitle));
        OnPropertyChanged(nameof(SecondaryPageTitle));
    }

    private PageAppearance AppearanceForDocument(PageDocument? document)
    {
        if (document == null) return new PageAppearance();
        var notebook = _library.Notebooks.FirstOrDefault(item => item.Id == document.NotebookId);
        return notebook?.FindPage(document.PageId, out _)?.Appearance ?? new PageAppearance();
    }

    private string TitleForDocument(PageDocument? document)
    {
        if (document == null) return "未选择页面";
        var notebook = _library.Notebooks.FirstOrDefault(item => item.Id == document.NotebookId);
        return notebook?.FindPage(document.PageId, out _)?.Title ?? "未选择页面";
    }
}
