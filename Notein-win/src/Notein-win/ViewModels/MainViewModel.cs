using System.Collections.ObjectModel;
using System.Windows.Input;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.ViewModels;

public class MainViewModel : ObservableObject
{
    private readonly NotebookLibrary _library;
    private readonly PageContentStorage _storage;
    private readonly ToolShelf _toolShelf;
    private readonly FullPageToolbarConfig _fullPageConfig;

    private bool _isSectionPaneVisible = true;
    private bool _isPagePaneVisible = true;
    private bool _isFullPageMode = false;
    private bool _isFloatingToolbarVisible = true;
    private bool _isToolFlyoutOpen = false;
    private RibbonTab _activeRibbonTab = RibbonTab.Draw;
    private CanvasBackgroundFormat _backgroundFormat = CanvasBackgroundFormat.GridStandard;
    private bool _singleFingerPanEnabled = false;
    private bool _textModeEnabled = false;
    private bool _insertSpaceModeEnabled = false;
    private CanvasTagKind? _pendingTagKind = null;
    private bool _isBackstageOpen = false;
    private string _backstageTab = "Info";
    private string _searchQuery = "";
    private bool _predictionEnabled = true;

    public NotebookLibrary Library => _library;
    public NotebookWorkspace ActiveWorkspace => _library.ActiveNotebook;
    public ToolShelf ToolShelf => _toolShelf;
    public FullPageToolbarConfig FullPageConfig => _fullPageConfig;

    public ObservableCollection<SectionRow> SectionRows { get; } = new();
    public ObservableCollection<PagePaneRow> PagePaneRows { get; } = new();
    public ObservableCollection<ToolPreset> Tools { get; } = new();

    public string ActiveNotebookTitle => ActiveWorkspace.Title;
    public string ActiveSectionTitle => ActiveWorkspace.SelectedSection()?.Title ?? (ActiveWorkspace.FocusedSectionId == null ? "笔记本根目录" : "未选中分区");
    public string ActivePageTitle => ActiveWorkspace.SelectedPage()?.Title ?? "无页面";
    public string BreadcrumbText => string.Join(" > ", ActiveWorkspace.Breadcrumb().Select(s => s.Title));

    public bool IsSectionPaneVisible
    {
        get => _isSectionPaneVisible;
        set => SetField(ref _isSectionPaneVisible, value);
    }

    public bool IsPagePaneVisible
    {
        get => _isPagePaneVisible;
        set => SetField(ref _isPagePaneVisible, value);
    }

    public bool IsFullPageMode
    {
        get => _isFullPageMode;
        set
        {
            if (SetField(ref _isFullPageMode, value))
            {
                OnPropertyChanged(nameof(IsNormalChromeVisible));
            }
        }
    }

    public bool IsNormalChromeVisible => !_isFullPageMode;

    public bool IsFloatingToolbarVisible
    {
        get => _isFloatingToolbarVisible;
        set => SetField(ref _isFloatingToolbarVisible, value);
    }

    public bool IsToolFlyoutOpen
    {
        get => _isToolFlyoutOpen;
        set => SetField(ref _isToolFlyoutOpen, value);
    }

    public RibbonTab ActiveRibbonTab
    {
        get => _activeRibbonTab;
        set => SetField(ref _activeRibbonTab, value);
    }

    public CanvasBackgroundFormat BackgroundFormat
    {
        get => _backgroundFormat;
        set => SetField(ref _backgroundFormat, value);
    }

    public bool SingleFingerPanEnabled
    {
        get => _singleFingerPanEnabled;
        set
        {
            if (SetField(ref _singleFingerPanEnabled, value) && value)
            {
                TextModeEnabled = false;
                InsertSpaceModeEnabled = false;
            }
        }
    }

    public bool TextModeEnabled
    {
        get => _textModeEnabled;
        set
        {
            if (SetField(ref _textModeEnabled, value) && value)
            {
                SingleFingerPanEnabled = false;
                InsertSpaceModeEnabled = false;
            }
        }
    }

    public bool InsertSpaceModeEnabled
    {
        get => _insertSpaceModeEnabled;
        set
        {
            if (SetField(ref _insertSpaceModeEnabled, value) && value)
            {
                SingleFingerPanEnabled = false;
                TextModeEnabled = false;
            }
        }
    }

    public CanvasTagKind? PendingTagKind
    {
        get => _pendingTagKind;
        set => SetField(ref _pendingTagKind, value);
    }

    public bool IsBackstageOpen
    {
        get => _isBackstageOpen;
        set => SetField(ref _isBackstageOpen, value);
    }

    public string BackstageTab
    {
        get => _backstageTab;
        set => SetField(ref _backstageTab, value);
    }

    public string SearchQuery
    {
        get => _searchQuery;
        set => SetField(ref _searchQuery, value);
    }

    public bool PredictionEnabled
    {
        get => _predictionEnabled;
        set => SetField(ref _predictionEnabled, value);
    }

    public PagePaneMode PagePaneMode
    {
        get => ActiveWorkspace.PagePaneMode;
        set
        {
            if (ActiveWorkspace.PagePaneMode != value)
            {
                _library.SetPagePaneMode(value);
                RefreshNavigation();
                OnPropertyChanged();
            }
        }
    }

    public bool CanAddPage => ActiveWorkspace.HasSelectedSection();

    public ToolPreset SelectedTool => _toolShelf.Selected;

    // Events for View/Canvas consumption
    public event Action? RequestUndo;
    public event Action? RequestClearPage;
    public event Action<CanvasTagKind>? RequestPlaceTag;

    // Commands
    public ICommand ToggleSectionPaneCommand { get; }
    public ICommand TogglePagePaneCommand { get; }
    public ICommand ToggleFullPageCommand { get; }
    public ICommand ToggleFloatingToolbarCommand { get; }

    public ICommand SelectNotebookCommand { get; }
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
    public ICommand DeleteSelectedToolCommand { get; }
    public ICommand TogglePanoramaHandCommand { get; }
    public ICommand ToggleTextModeCommand { get; }
    public ICommand ToggleInsertSpaceCommand { get; }
    public ICommand ArmTagPlacementCommand { get; }
    public ICommand SetBackgroundFormatCommand { get; }

    public ICommand OpenBackstageCommand { get; }
    public ICommand CloseBackstageCommand { get; }
    public ICommand SelectBackstageTabCommand { get; }

    public ICommand UndoCommand { get; }
    public ICommand ClearPageCommand { get; }

    public MainViewModel()
    {
        _library = NotebookLibrary.Sample();
        _storage = new PageContentStorage();
        _toolShelf = new ToolShelf();
        _fullPageConfig = new FullPageToolbarConfig();

        ToggleSectionPaneCommand = new RelayCommand(() => IsSectionPaneVisible = !IsSectionPaneVisible);
        TogglePagePaneCommand = new RelayCommand(() => IsPagePaneVisible = !IsPagePaneVisible);
        ToggleFullPageCommand = new RelayCommand(() => IsFullPageMode = !IsFullPageMode);
        ToggleFloatingToolbarCommand = new RelayCommand(() => IsFloatingToolbarVisible = !IsFloatingToolbarVisible);

        SelectNotebookCommand = new RelayCommand<string>(id =>
        {
            if (id != null && _library.SelectNotebook(id))
            {
                RefreshNavigation();
                OnPropertyChanged(nameof(ActiveWorkspace));
                OnPropertyChanged(nameof(ActiveNotebookTitle));
            }
        });

        AddNotebookCommand = new RelayCommand(() =>
        {
            _library.AddNotebook("新笔记本");
            RefreshNavigation();
            OnPropertyChanged(nameof(ActiveWorkspace));
            OnPropertyChanged(nameof(ActiveNotebookTitle));
        });

        SelectSectionCommand = new RelayCommand<string>(id =>
        {
            if (id != null)
            {
                ActiveWorkspace.SelectSection(id);
                RefreshNavigation();
            }
        });

        FocusNotebookRootCommand = new RelayCommand(() =>
        {
            ActiveWorkspace.FocusNotebook();
            RefreshNavigation();
        });

        AddSectionCommand = new RelayCommand(() =>
        {
            ActiveWorkspace.AddSectionAtFocus();
            RefreshNavigation();
        });

        ToggleSectionExpandCommand = new RelayCommand<string>(id =>
        {
            if (id != null)
            {
                ActiveWorkspace.ToggleExpanded(id);
                RefreshSectionRows();
            }
        });

        SelectPageCommand = new RelayCommand<PagePaneRow.PageItemRow>(item =>
        {
            if (item != null)
            {
                ActiveWorkspace.SelectPage(item.SectionId, item.Page.Id);
                RefreshPagePaneRows();
                OnPropertyChanged(nameof(ActivePageTitle));
                OnPropertyChanged(nameof(ActiveSectionTitle));
                OnPropertyChanged(nameof(BreadcrumbText));
            }
        });

        AddPageCommand = new RelayCommand(() =>
        {
            if (CanAddPage)
            {
                ActiveWorkspace.AddPage("新页面");
                RefreshPagePaneRows();
                OnPropertyChanged(nameof(ActivePageTitle));
            }
        }, () => CanAddPage);

        SetPagePaneModeCommand = new RelayCommand<PagePaneMode>(mode => PagePaneMode = mode);

        SelectRibbonTabCommand = new RelayCommand<RibbonTab>(tab => ActiveRibbonTab = tab);

        SelectToolCommand = new RelayCommand<string>(id =>
        {
            if (id != null)
            {
                if (_toolShelf.SelectedId == id)
                {
                    IsToolFlyoutOpen = !IsToolFlyoutOpen;
                }
                else
                {
                    _toolShelf.Select(id);
                    IsToolFlyoutOpen = false;
                    SingleFingerPanEnabled = false;
                    TextModeEnabled = false;
                    InsertSpaceModeEnabled = false;
                    OnPropertyChanged(nameof(SelectedTool));
                }
            }
        });

        AddToolCommand = new RelayCommand<DrawingToolKind>(kind =>
        {
            _toolShelf.Add(kind);
            RefreshTools();
            OnPropertyChanged(nameof(SelectedTool));
        });

        DeleteSelectedToolCommand = new RelayCommand(() =>
        {
            if (_toolShelf.DeleteSelected())
            {
                RefreshTools();
                OnPropertyChanged(nameof(SelectedTool));
            }
        });

        TogglePanoramaHandCommand = new RelayCommand(() => SingleFingerPanEnabled = !SingleFingerPanEnabled);
        ToggleTextModeCommand = new RelayCommand(() => TextModeEnabled = !TextModeEnabled);
        ToggleInsertSpaceCommand = new RelayCommand(() => InsertSpaceModeEnabled = !InsertSpaceModeEnabled);

        ArmTagPlacementCommand = new RelayCommand<CanvasTagKind>(kind =>
        {
            PendingTagKind = kind;
            RequestPlaceTag?.Invoke(kind);
        });

        SetBackgroundFormatCommand = new RelayCommand<CanvasBackgroundFormat>(fmt => BackgroundFormat = fmt);

        OpenBackstageCommand = new RelayCommand(() => IsBackstageOpen = true);
        CloseBackstageCommand = new RelayCommand(() => IsBackstageOpen = false);
        SelectBackstageTabCommand = new RelayCommand<string>(tab => BackstageTab = tab ?? "Info");

        UndoCommand = new RelayCommand(() => RequestUndo?.Invoke());
        ClearPageCommand = new RelayCommand(() => RequestClearPage?.Invoke());

        RefreshNavigation();
        RefreshTools();
    }

    public void RefreshNavigation()
    {
        RefreshSectionRows();
        RefreshPagePaneRows();
        OnPropertyChanged(nameof(ActiveNotebookTitle));
        OnPropertyChanged(nameof(ActiveSectionTitle));
        OnPropertyChanged(nameof(ActivePageTitle));
        OnPropertyChanged(nameof(BreadcrumbText));
        OnPropertyChanged(nameof(CanAddPage));
        OnPropertyChanged(nameof(PagePaneMode));
    }

    private void RefreshSectionRows()
    {
        SectionRows.Clear();
        foreach (var row in ActiveWorkspace.FlattenSections())
        {
            SectionRows.Add(row);
        }
    }

    private void RefreshPagePaneRows()
    {
        PagePaneRows.Clear();
        foreach (var row in ActiveWorkspace.GetPagePaneRows())
        {
            PagePaneRows.Add(row);
        }
    }

    private void RefreshTools()
    {
        Tools.Clear();
        foreach (var tool in _toolShelf.Tools)
        {
            Tools.Add(tool);
        }
    }
}
