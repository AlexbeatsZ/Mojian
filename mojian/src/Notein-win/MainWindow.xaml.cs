using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Interop;
using Microsoft.Win32;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win;

public partial class MainWindow : Window
{
    private bool _eventsWired;
    private TextBox? _lastTextBox;
    private TextBox? _clipboardTextBox;
    private TextStyleSnapshot? _formatPainter;
    private PageNavigationClipboard? _pageNavigationClipboard;
    private bool _toolFlyoutWasOpenAtPointerDown;
    private string? _toolFlyoutPointerToolId;
    private MainViewModel ViewModel => (MainViewModel)DataContext;
    private NoteCanvas FocusedCanvas => ViewModel.FocusedSlot == CanvasSlot.Right ? RightCanvas : LeftCanvas;

    public MainWindow() : this(new MainViewModel()) { }

    public MainWindow(MainViewModel viewModel)
    {
        DataContext = viewModel;
        InitializeComponent();
        Loaded += OnLoaded;
        SourceInitialized += OnSourceInitialized;
        PreviewGotKeyboardFocus += (_, e) =>
        {
            if (e.NewFocus is TextBox box && FindAncestor<NoteCanvas>(box) != null) { _lastTextBox = box; _clipboardTextBox = box; }
            else if (e.NewFocus is NoteCanvas || e.NewFocus is DependencyObject focus && FindAncestor<NoteCanvas>(focus) != null) _clipboardTextBox = null;
        };
    }

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        var handle = new WindowInteropHelper(this).Handle;
        var dark = 1;
        var caption = 0x00141414;
        var textColor = 0x00F4F4F4;
        _ = DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int));
        _ = DwmSetWindowAttribute(handle, 35, ref caption, sizeof(int));
        _ = DwmSetWindowAttribute(handle, 36, ref textColor, sizeof(int));
        HwndSource.FromHwnd(handle)?.AddHook(CaptionWndProc);
        StateChanged += (_, _) => ApplyWindowStateChrome();
        DpiChanged += (_, _) => ApplyWindowStateChrome();
        ApplyWindowStateChrome();
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int valueSize);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetricsForDpi(int index, uint dpi);

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    private const int WmNcHitTest = 0x0084;
    private const int WmNcLButtonDown = 0x00A1;
    private const int WmNcLButtonUp = 0x00A2;
    private const int WmNcMouseLeave = 0x02A2;
    private const int HtMaxButton = 9;
    private bool _maximizeCaptionHot;
    private bool _maximizeCaptionPressed;

    private void OnMinimizeCaptionClick(object sender, RoutedEventArgs e) => SystemCommands.MinimizeWindow(this);
    private void OnMaximizeCaptionClick(object sender, RoutedEventArgs e) => ToggleMaximized();
    private void OnCloseCaptionClick(object sender, RoutedEventArgs e) => SystemCommands.CloseWindow(this);

    private void ToggleMaximized()
    {
        if (WindowState == WindowState.Maximized) SystemCommands.RestoreWindow(this);
        else SystemCommands.MaximizeWindow(this);
    }

    // WindowChrome lets a maximized client area overhang the monitor by the sizing frame; inset the
    // layout so the top row and caption buttons stay on screen.
    private void ApplyWindowStateChrome()
    {
        var maximized = WindowState == WindowState.Maximized;
        MaximizeCaptionButton.Content = maximized ? "" : "";
        MaximizeCaptionButton.ToolTip = maximized ? "向下还原" : "最大化";
        if (!maximized) { RootLayout.Margin = new Thickness(0); return; }
        var handle = new WindowInteropHelper(this).Handle;
        var dpi = handle == IntPtr.Zero ? 96u : GetDpiForWindow(handle);
        if (dpi == 0) dpi = 96;
        const int smCxSizeFrame = 32, smCySizeFrame = 33, smCxPaddedBorder = 92;
        var padded = GetSystemMetricsForDpi(smCxPaddedBorder, dpi);
        var scale = dpi / 96.0;
        var x = (GetSystemMetricsForDpi(smCxSizeFrame, dpi) + padded) / scale;
        var y = (GetSystemMetricsForDpi(smCySizeFrame, dpi) + padded) / scale;
        RootLayout.Margin = new Thickness(x, y, x, y);
    }

    // Reports the maximize button as HTMAXBUTTON so Windows 11 shows Snap Layouts on hover; the
    // non-client press/release is then handled here instead of by the WPF button.
    private IntPtr CaptionWndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        switch (msg)
        {
            case WmNcHitTest:
                if (IsOverMaximizeCaption(lParam))
                {
                    SetMaximizeCaptionState(true, _maximizeCaptionPressed);
                    handled = true;
                    return new IntPtr(HtMaxButton);
                }
                SetMaximizeCaptionState(false, false);
                break;
            case WmNcMouseLeave:
                SetMaximizeCaptionState(false, false);
                break;
            case WmNcLButtonDown when wParam.ToInt64() == HtMaxButton:
                SetMaximizeCaptionState(true, true);
                handled = true;
                break;
            case WmNcLButtonUp when wParam.ToInt64() == HtMaxButton:
                var activate = _maximizeCaptionPressed;
                SetMaximizeCaptionState(true, false);
                handled = true;
                if (activate) Dispatcher.InvokeAsync(ToggleMaximized);
                break;
        }
        return IntPtr.Zero;
    }

    private bool IsOverMaximizeCaption(IntPtr lParam)
    {
        if (!MaximizeCaptionButton.IsVisible) return false;
        var raw = lParam.ToInt64();
        var screen = new Point((short)(raw & 0xFFFF), (short)((raw >> 16) & 0xFFFF));
        var local = MaximizeCaptionButton.PointFromScreen(screen);
        return local.X >= 0 && local.Y >= 0 && local.X < MaximizeCaptionButton.ActualWidth && local.Y < MaximizeCaptionButton.ActualHeight;
    }

    private void SetMaximizeCaptionState(bool hot, bool pressed)
    {
        if (_maximizeCaptionHot == hot && _maximizeCaptionPressed == pressed) return;
        _maximizeCaptionHot = hot;
        _maximizeCaptionPressed = pressed;
        MaximizeCaptionButton.Background = !hot ? Brushes.Transparent
            : new SolidColorBrush(pressed ? Color.FromRgb(0x3A, 0x3A, 0x3A) : Color.FromRgb(0x2D, 0x2D, 0x2D));
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_eventsWired) return;
        _eventsWired = true;
        ViewModel.ObserveSaveCompletion(Dispatcher);
        var syncTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        syncTimer.Tick += (_, _) =>
        {
            if (System.Windows.Input.Mouse.LeftButton == System.Windows.Input.MouseButtonState.Pressed ||
                System.Windows.Input.Stylus.CurrentStylusDevice?.InAir == false ||
                Keyboard.FocusedElement is System.Windows.Controls.TextBox) return;
            try { ViewModel.RefreshSharedWorkspace(); }
            catch (Exception) { /* A partial incoming file is retried after the next sync interval. */ }
        };
        syncTimer.Start();
        Closed += (_, _) => syncTimer.Stop();
        ConfigureCanvas(LeftCanvas);
        ConfigureCanvas(RightCanvas);
        ViewModel.RequestUndo += () => FocusedCanvas.Undo();
        ViewModel.RequestRedo += () => FocusedCanvas.Redo();
        ViewModel.RequestClearPage += () => FocusedCanvas.ClearPage();
        ViewModel.RequestZoom += scale => FocusedCanvas.SetZoom(scale ?? 1f);
        ViewModel.RequestFitPageWidth += () => FocusedCanvas.FitPageWidth();
        ViewModel.RequestAppearanceRefresh += () => { LeftCanvas.InvalidateVisual(); RightCanvas.InvalidateVisual(); };
        Backstage.NewNotebookRequested += (_, _) => NewNotebook();
        Backstage.OpenWorkspaceRequested += (_, _) => OpenWorkspaceFolder();
        Backstage.SwitchWorkspaceRequested += (_, _) => SwitchWorkspace();
        Backstage.ExportPdfRequested += (_, _) => ExportCurrentPagePdf();
        Backstage.ExportPackageRequested += (_, _) => ExportNotebookPackage();
        ConfigureExportPanels();
        Backstage.ConfigureShortcutRequested += ConfigureShortcut;
        Backstage.ClearShortcutRequested += action => ViewModel.ClearShortcut(action.Id);
        UpdateZoomStatus();
    }

    private void ConfigureCanvas(NoteCanvas canvas)
    {
        canvas.ResolveAssetPath = name => canvas.Document == null ? string.Empty : ViewModel.ResolveAsset(canvas.Document, name);
        canvas.ImportAssetPath = ViewModel.ImportAsset;
        canvas.ImportClipboardAsset = (name, bytes) => ViewModel.WriteAsset(Path.GetExtension(name), bytes);
        canvas.PasteRequested += (_, _) => PasteHomeContent();
        canvas.ResolvePressureEraserTool = () => ViewModel.PressureEraserTool;
        canvas.ResolveShapeSettings = () => ViewModel.ShapeSettings;
        canvas.BeginSpringLoadedTool = ViewModel.BeginSpringLoadedTool;
        canvas.EndSpringLoadedTool = ViewModel.EndSpringLoadedTool;
        canvas.DocumentChanged += (_, _) =>
        {
            ViewModel.QueueSaveDocument(canvas.Document);
            var other = canvas == LeftCanvas ? RightCanvas : LeftCanvas;
            if (ReferenceEquals(other.Document, canvas.Document)) other.InvalidateVisual();
        };
        canvas.ViewportChanged += (_, _) => { if (canvas == FocusedCanvas) UpdateZoomStatus(); };
        canvas.PageMetadataChanged += (_, _) => QueuePageMetadataSave();
        canvas.ObjectContextRequested += OnObjectContextRequested;
    }

    private bool _pageMetadataSavePending;

    // Saving the library flushes the page save queue and writes to disk; keep it out
    // of the pointer-up handler and coalesce repeated growth from consecutive gestures.
    private void QueuePageMetadataSave()
    {
        if (_pageMetadataSavePending) return;
        _pageMetadataSavePending = true;
        Dispatcher.BeginInvoke(() =>
        {
            _pageMetadataSavePending = false;
            ViewModel.SavePageMetadata();
        }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
    }

    private void OnObjectActivated(object? sender, PageObjectEventArgs e)
    {
        if (e.Value.Kind == PageObjectKind.Table && sender is NoteCanvas tableCanvas)
        {
            var source = string.Join(Environment.NewLine, Enumerable.Range(0, Math.Max(1, e.Value.Rows))
                .Select(row => string.Join('\t', Enumerable.Range(0, Math.Max(1, e.Value.Columns))
                    .Select(column => e.Value.Cells.ElementAtOrDefault(row * Math.Max(1, e.Value.Columns) + column) ?? string.Empty))));
            var edited = PromptMultiline("编辑表格", "每行是一行，单元格用 Tab 分隔", source);
            if (edited == null) return;
            var rows = edited.ReplaceLineEndings("\n").Split('\n');
            var cells = rows.Select(row => row.Split('\t')).ToList();
            var columns = Math.Max(1, cells.Max(row => row.Length));
            tableCanvas.UpdateObject(e.Value, item =>
            {
                item.Rows = Math.Max(1, rows.Length);
                item.Columns = columns;
                item.Cells = cells.SelectMany(row => row.Concat(Enumerable.Repeat(string.Empty, columns - row.Length))).ToList();
                item.Width = Math.Max(180, columns * 90);
                item.Height = Math.Max(90, rows.Length * 36);
            });
            return;
        }
        if (e.Value.Kind == PageObjectKind.NoteLink)
        {
            if (!ViewModel.NavigateToPage(e.Value.TargetNotebookId, e.Value.TargetPageId))
                MessageBox.Show(this, "目标页面已不存在。", "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        if (sender is not NoteCanvas canvas || canvas.Document == null || string.IsNullOrWhiteSpace(e.Value.AssetName)) return;
        var path = ViewModel.ResolveAsset(canvas.Document, e.Value.AssetName);
        if (File.Exists(path)) Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    private void OnObjectContextRequested(object? sender, PageObjectEventArgs e)
    {
        if (sender is not NoteCanvas canvas || canvas.Document == null) return;
        var item = e.Value;
        var menu = new ContextMenu();

        AddMenuItem(menu, item.Kind == PageObjectKind.Table ? "编辑" : "打开", () => OnObjectActivated(canvas, e));
        menu.Items.Add(new Separator());

        AddMenuItem(menu, "剪切", () => canvas.CopySelection(cut: true));
        AddMenuItem(menu, "复制", () => canvas.CopySelection());
        AddMenuItem(menu, "复制为 SVG", () => canvas.CopySelectionImage(true));
        AddMenuItem(menu, "复制为 PNG", () => canvas.CopySelectionImage(false));
        AddMenuItem(menu, "粘贴", PasteHomeContent);
        menu.Items.Add(new Separator());

        var saveAs = AddMenuItem(menu, "另存为…", () => SaveEmbeddedObject(canvas, item));
        saveAs.IsEnabled = !string.IsNullOrWhiteSpace(item.AssetName);
        AddMenuItem(menu, "复制指向所在页面的链接", () => CopyOwningPageLink(canvas));

        var order = new MenuItem { Header = "层级" };
        AddMenuItem(order, "上移一层", () => canvas.MoveObjectForward(item));
        AddMenuItem(order, "置于顶层", () => canvas.MoveObjectToFront(item));
        AddMenuItem(order, "下移一层", () => canvas.MoveObjectBackward(item));
        AddMenuItem(order, "置于底层", () => canvas.MoveObjectToBack(item));
        menu.Items.Add(order);

        if (item.IsImageLike)
        {
            var invert = AddMenuItem(menu, "反色", () => canvas.ToggleImageInversion(item));
            invert.IsCheckable = true;
            invert.IsChecked = item.ViewInverted;
            var rotate = new MenuItem { Header = "旋转" };
            AddMenuItem(rotate, "顺时针旋转 90°", () => canvas.RotateImageObject(item, clockwise: true));
            AddMenuItem(rotate, "逆时针旋转 90°", () => canvas.RotateImageObject(item, clockwise: false));
            menu.Items.Add(rotate);

            var flip = new MenuItem { Header = "翻转" };
            AddMenuItem(flip, "水平翻转", () => canvas.FlipImageObject(item, horizontal: true));
            AddMenuItem(flip, "垂直翻转", () => canvas.FlipImageObject(item, horizontal: false));
            menu.Items.Add(flip);
            AddMenuItem(menu, "还原原始大小", () => canvas.RestoreImageObjectSize(item));
            AddMenuItem(menu, "设为背景", () => canvas.SetImageObjectAsBackground(item));
        }

        var crop = new MenuItem { Header = "裁剪", IsEnabled = false };
        var move = new MenuItem { Header = "移动到其他页面", IsEnabled = false };
        menu.Items.Add(crop);
        menu.Items.Add(move);
        menu.Items.Add(new Separator());
        AddMenuItem(menu, "删除", () => canvas.RemoveObject(item));
        canvas.AttachRightButtonLassoDismissal(menu);
        OpenContextMenu(canvas, menu);
    }


    private void SaveEmbeddedObject(NoteCanvas canvas, PageObject item)
    {
        if (canvas.Document == null || string.IsNullOrWhiteSpace(item.AssetName)) return;
        var source = ViewModel.ResolveAsset(canvas.Document, item.AssetName);
        if (!File.Exists(source)) return;
        var extension = Path.GetExtension(item.DisplayName);
        if (string.IsNullOrWhiteSpace(extension)) extension = Path.GetExtension(source);
        var dialog = new SaveFileDialog
        {
            Title = "另存嵌入文件",
            FileName = string.IsNullOrWhiteSpace(item.DisplayName) ? $"嵌入文件{extension}" : item.DisplayName,
            Filter = string.IsNullOrWhiteSpace(extension) ? "所有文件 (*.*)|*.*" : $"{extension} 文件 (*{extension})|*{extension}|所有文件 (*.*)|*.*",
        };
        if (dialog.ShowDialog(this) == true) File.Copy(source, dialog.FileName, overwrite: true);
    }

    private void CopyOwningPageLink(NoteCanvas canvas)
    {
        if (canvas.Document == null) return;
        var notebook = ViewModel.ActiveWorkspace;
        var page = notebook.FindPage(canvas.Document.PageId, out var owner);
        if (page != null && owner != null) Clipboard.SetText($"mojian://notebook/{notebook.Id}/section/{owner.Id}/page/{page.Id}");
    }

    private void OnAddSectionMenuClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        AddMenuItem(menu, "新建笔记本", NewNotebook);
        AddMenuItem(menu, "新建分区", () =>
        {
            var title = Prompt("新建分区", "分区名称", "新分区");
            if (title != null) ViewModel.AddSection(title);
        });
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnNotebookRowRightClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement anchor || anchor.DataContext is not NotebookPaneRow row) return;
        var notebook = ViewModel.Library.Notebooks.FirstOrDefault(item => item.Id == row.Id);
        if (notebook == null) return;
        var menu = new ContextMenu();
        AddMenuItem(menu, "重命名", () =>
        {
            var title = Prompt("重命名笔记本", "笔记本名称", notebook.Title);
            if (title != null) ViewModel.RenameNotebook(notebook.Id, title);
        });
        AddMenuItem(menu, "删除", () =>
        {
            var sections = notebook.FlattenAllSections().Count();
            var pages = notebook.FlattenAllSections().Sum(section => section.Pages.Count);
            if (MessageBox.Show(this, $"将删除“{notebook.Title}”以及其中 {sections} 个分区、{pages} 个页面和本地附件。此操作无法撤销。", "删除笔记本？", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel) != MessageBoxResult.OK) return;
            if (ViewModel.DeleteNotebook(notebook.Id) && _pageNavigationClipboard?.NotebookId == notebook.Id)
                _pageNavigationClipboard = null;
        });
        menu.Items.Add(new Separator());
        AddMenuItem(menu, "新建分区", () =>
        {
            var title = Prompt("新建分区", "分区名称", "新分区");
            if (title == null) return;
            ViewModel.SelectNotebookCommand.Execute(notebook.Id);
            ViewModel.AddSection(title);
        });
        anchor.ContextMenu = menu;
        OpenContextMenu(anchor, menu);
        e.Handled = true;
    }

    private void OnSectionRowRightClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement anchor || anchor.DataContext is not SectionRow row) return;
        var section = row.Section;
        var notebook = ViewModel.ActiveWorkspace;
        var menu = new ContextMenu();

        AddMenuItem(menu, "重命名", () =>
        {
            var title = Prompt("重命名分区", "分区名称", section.Title);
            if (title != null) ViewModel.RenameSection(section.Id, title);
        });
        AddMenuItem(menu, "导出…", () => ExportSection(section));
        AddMenuItem(menu, "删除", () =>
        {
            var pages = PagesInSectionTree(section).Count();
            var children = FlattenSections(section.Children).Count();
            var detail = pages + children > 0 ? $"以及其中 {children} 个子分区、{pages} 个页面" : string.Empty;
            if (MessageBox.Show(this, $"将删除“{section.Title}”{detail}。此操作无法撤销。", "删除分区？", MessageBoxButton.OKCancel, MessageBoxImage.Warning) == MessageBoxResult.OK)
                ViewModel.DeleteSection(section.Id);
        });

        var moveOrCopy = new MenuItem { Header = "移动或复制…" };
        var moveMenu = new MenuItem { Header = "移动到" };
        var copyMenu = new MenuItem { Header = "复制到" };
        foreach (var (label, parentId) in SectionParentTargets(notebook, section))
        {
            var capturedParentId = parentId;
            AddMenuItem(moveMenu, label, () => ViewModel.MoveSection(section.Id, capturedParentId));
            AddMenuItem(copyMenu, label, () => ViewModel.CopySection(section.Id, capturedParentId));
        }
        moveOrCopy.Items.Add(moveMenu);
        moveOrCopy.Items.Add(copyMenu);
        menu.Items.Add(moveOrCopy);

        var mergeMenu = new MenuItem { Header = "与其他分区合并…" };
        foreach (var target in notebook.FlattenAllSections().Where(candidate => candidate.Id != section.Id && !SectionContains(section, candidate.Id)))
        {
            var captured = target;
            AddMenuItem(mergeMenu, SectionPath(notebook, target), () =>
            {
                if (MessageBox.Show(this, $"“{section.Title}”中的页面和子分区将移入“{captured.Title}”，原分区会被删除。", "合并分区？", MessageBoxButton.OKCancel, MessageBoxImage.Warning) == MessageBoxResult.OK)
                    ViewModel.MergeSection(section.Id, captured.Id);
            });
        }
        mergeMenu.IsEnabled = mergeMenu.Items.Count > 0;
        menu.Items.Add(mergeMenu);
        AddMenuItem(menu, "复制指向分区的链接", () => Clipboard.SetText($"mojian://notebook/{notebook.Id}/section/{section.Id}"));
        menu.Items.Add(new Separator());
        AddMenuItem(menu, "新建分区", () =>
        {
            var title = Prompt("新建分区", "分区名称", "新分区");
            if (title != null) ViewModel.AddChildSection(section.Id, title);
        });
        OpenContextMenu(anchor, menu);
        e.Handled = true;
    }

    private void OnPageRowRightClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement anchor || anchor.DataContext is not PagePaneRow row || row.Page == null || row.IsHeader) return;
        var notebook = ViewModel.ActiveWorkspace;
        var page = row.Page;
        var menu = new ContextMenu();
        AddMenuItem(menu, "重命名", () =>
        {
            var title = Prompt("重命名页面", "页面标题", page.Title);
            if (title != null) ViewModel.RenamePage(row.SectionId, page.Id, title);
        });
        AddMenuItem(menu, "删除", () =>
        {
            if (MessageBox.Show(this, $"将删除“{page.Title}”及其本地内容。此操作无法撤销。", "删除页面？", MessageBoxButton.OKCancel, MessageBoxImage.Warning) == MessageBoxResult.OK)
            {
                if (ViewModel.DeletePage(row.SectionId, page.Id) && _pageNavigationClipboard?.PageId == page.Id) _pageNavigationClipboard = null;
            }
        });
        AddMenuItem(menu, "剪切", () => _pageNavigationClipboard = new PageNavigationClipboard(notebook.Id, row.SectionId, page.Id, true));
        AddMenuItem(menu, "复制", () => _pageNavigationClipboard = new PageNavigationClipboard(notebook.Id, row.SectionId, page.Id, false));
        var paste = AddMenuItem(menu, "粘贴", () => PastePageAfter(row.SectionId, page.Id));
        paste.IsEnabled = _pageNavigationClipboard != null;
        AddMenuItem(menu, "复制指向页面的链接", () => Clipboard.SetText($"mojian://notebook/{notebook.Id}/section/{row.SectionId}/page/{page.Id}"));

        var moveOrCopy = new MenuItem { Header = "移动或复制…" };
        var moveMenu = new MenuItem { Header = "移动到" };
        var copyMenu = new MenuItem { Header = "复制到" };
        foreach (var target in notebook.FlattenAllSections())
        {
            var captured = target;
            var label = SectionPath(notebook, target);
            AddMenuItem(moveMenu, label, () => ViewModel.MovePage(row.SectionId, page.Id, captured.Id));
            AddMenuItem(copyMenu, label, () => ViewModel.CopyPage(row.SectionId, page.Id, captured.Id));
        }
        moveOrCopy.Items.Add(moveMenu);
        moveOrCopy.Items.Add(copyMenu);
        menu.Items.Add(moveOrCopy);
        OpenContextMenu(anchor, menu);
        e.Handled = true;
    }

    private void PastePageAfter(string targetSectionId, string targetPageId)
    {
        var clip = _pageNavigationClipboard;
        if (clip == null || clip.NotebookId != ViewModel.ActiveWorkspace.Id) return;
        if (ViewModel.ActiveWorkspace.FindPage(clip.PageId, out var sourceOwner) == null || sourceOwner == null) { _pageNavigationClipboard = null; return; }
        if (clip.IsCut)
        {
            if (ViewModel.MovePageAdjacent(sourceOwner.Id, clip.PageId, targetSectionId, targetPageId, after: true)) _pageNavigationClipboard = null;
            return;
        }
        var target = ViewModel.ActiveWorkspace.FindSection(targetSectionId);
        var targetIndex = target?.Pages.FindIndex(item => item.Id == targetPageId) ?? -1;
        if (targetIndex >= 0) ViewModel.CopyPage(sourceOwner.Id, clip.PageId, targetSectionId, targetIndex + 1);
    }

    private void ExportSection(SectionNode section)
    {
        var dialog = new SaveFileDialog { Title = "导出墨笺分区", Filter = "墨笺分区 (*.mjn)|*.mjn", FileName = $"{section.Title}.mjn", AddExtension = true, DefaultExt = ".mjn" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            ViewModel.SaveAll();
            var currentPageId = ViewModel.FocusedDocument?.PageId;
            if (currentPageId != null && PagesInSectionTree(section).All(page => page.Id != currentPageId)) currentPageId = null;
            var layers = currentPageId == null ? null : CaptureCurrentLayers();
            NotebookPackageService.ExportSection(dialog.FileName, ViewModel.ActiveWorkspace, section, ViewModel.Store, currentPageId, layers);
            MessageBox.Show(this, "分区包已导出。", "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) { MessageBox.Show(this, $"分区导出失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private Dictionary<string, byte[]> CaptureCurrentLayers() => new()
    {
        ["页面背景"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Background)),
        ["插入内容"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Inserts)),
        ["墨迹与文字"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.InkAndText)),
    };

    private static IEnumerable<(string Label, string? ParentId)> SectionParentTargets(NotebookWorkspace notebook, SectionNode source)
    {
        yield return (notebook.Title, null);
        foreach (var candidate in notebook.FlattenAllSections().Where(candidate => candidate.Id != source.Id && !SectionContains(source, candidate.Id)))
            yield return (SectionPath(notebook, candidate), candidate.Id);
    }

    private static string SectionPath(NotebookWorkspace notebook, SectionNode section) =>
        string.Join(" / ", notebook.Breadcrumb(section.Id).Select(node => node.Title));

    private static bool SectionContains(SectionNode section, string id) => section.Id == id || section.Children.Any(child => SectionContains(child, id));

    private static IEnumerable<SectionNode> FlattenSections(IEnumerable<SectionNode> roots)
    {
        foreach (var section in roots)
        {
            yield return section;
            foreach (var child in FlattenSections(section.Children)) yield return child;
        }
    }

    private static IEnumerable<NotePage> PagesInSectionTree(SectionNode section)
    {
        foreach (var page in section.Pages) yield return page;
        foreach (var child in section.Children)
            foreach (var page in PagesInSectionTree(child))
                yield return page;
    }

    private static void OpenContextMenu(FrameworkElement anchor, ContextMenu menu)
    {
        menu.PlacementTarget = anchor;
        menu.Placement = PlacementMode.MousePoint;
        menu.IsOpen = true;
    }

    private void OnAddToolMenuClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement button) return;
        var menu = new ContextMenu();
        AddMenuItem(menu, "添加钢笔", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Pen));
        AddMenuItem(menu, "添加荧光笔", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Highlighter));
        AddMenuItem(menu, "添加橡皮", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Eraser));
        AddMenuItem(menu, "添加套索", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Lasso));
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnToolPresetClick(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton button || button.DataContext is not ToolPreset tool) return;
        ToolFlyoutPopup.PlacementTarget = button;
        ViewModel.SelectToolFromToolbar(tool.Id, ConsumeToolFlyoutPointerDown(tool.Id));
    }

    private void OnToolPresetPreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement button || ToolIdForToolbarElement(button) is not { } id) return;
        if (_toolFlyoutWasOpenAtPointerDown && string.Equals(_toolFlyoutPointerToolId, id, StringComparison.Ordinal)) return;
        _toolFlyoutPointerToolId = id;
        _toolFlyoutWasOpenAtPointerDown = ToolFlyoutPopup.IsOpen || ViewModel.IsToolFlyoutOpen;
    }

    private void OnWindowPreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        if (!ToolFlyoutPopup.IsOpen && !ViewModel.IsToolFlyoutOpen)
        {
            _toolFlyoutWasOpenAtPointerDown = false;
            _toolFlyoutPointerToolId = null;
            return;
        }
        if (e.OriginalSource is not DependencyObject source || IsWithin(source, ToolFlyoutPopup.Child)) return;

        var toolButton = FindAncestor<ToggleButton>(source);
        _toolFlyoutWasOpenAtPointerDown = true;
        _toolFlyoutPointerToolId = toolButton == null ? null : ToolIdForToolbarElement(toolButton);
        ViewModel.IsToolFlyoutOpen = false;
    }

    private void OnWindowDeactivated(object? sender, EventArgs e)
    {
        LeftCanvas.CancelShapeAuthoring();
        RightCanvas.CancelShapeAuthoring();
        ViewModel.IsToolFlyoutOpen = false;
        PanoramaFlyoutPopup.IsOpen = false;
        _toolFlyoutWasOpenAtPointerDown = false;
        _toolFlyoutPointerToolId = null;
    }

    private bool ConsumeToolFlyoutPointerDown(string id)
    {
        var wasOpen = _toolFlyoutWasOpenAtPointerDown && string.Equals(_toolFlyoutPointerToolId, id, StringComparison.Ordinal);
        _toolFlyoutWasOpenAtPointerDown = false;
        _toolFlyoutPointerToolId = null;
        return wasOpen;
    }

    private static string? ToolIdForToolbarElement(FrameworkElement button)
    {
        if (button.DataContext is ToolPreset tool) return tool.Id;
        return button.Tag is string tag && tag.StartsWith("tool:", StringComparison.Ordinal) ? tag[5..] : null;
    }

    private static bool IsWithin(DependencyObject source, DependencyObject? ancestor)
    {
        if (ancestor == null) return false;
        for (var current = source; current != null; current = VisualTreeHelper.GetParent(current))
            if (ReferenceEquals(current, ancestor)) return true;
        return false;
    }

    private void OnPanoramaToolClick(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton button) return;
        if (!ViewModel.SingleFingerPanEnabled)
        {
            ViewModel.SingleFingerPanEnabled = true;
            PanoramaFlyoutPopup.IsOpen = false;
            return;
        }

        PanoramaFlyoutPopup.PlacementTarget = button;
        PanoramaFlyoutPopup.IsOpen = !PanoramaFlyoutPopup.IsOpen;
    }

    private void OnFullPageToolClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement button || button.Tag is not string id) return;
        if (id.StartsWith("tool:", StringComparison.Ordinal))
        {
            ToolFlyoutPopup.PlacementTarget = button;
            var toolId = id[5..];
            ViewModel.SelectToolFromToolbar(toolId, ConsumeToolFlyoutPointerDown(toolId));
            return;
        }
        if (id.StartsWith("home:", StringComparison.Ordinal))
        {
            var command = Enum.GetValues<HomeCommand>().FirstOrDefault(item => item.GetId() == id[5..]);
            var oldTag = button.Tag;
            button.Tag = command.ToString();
            OnHomeCommandClick(button, e);
            button.Tag = oldTag;
            return;
        }
        switch (id)
        {
            case "draw:undo": FocusedCanvas.Undo(); break;
            case "draw:redo": FocusedCanvas.Redo(); break;
            case "draw:add": OnAddToolMenuClick(button, e); break;
            case "draw:pan": ViewModel.SingleFingerPanEnabled = !ViewModel.SingleFingerPanEnabled; break;
            case "draw:insert_space": ViewModel.InsertSpaceModeEnabled = !ViewModel.InsertSpaceModeEnabled; break;
            case "draw:rule_lines": OnBackgroundFormatClick(button, e); break;
            case "draw:clear": OnClearPageClick(button, e); break;
        }
    }

    private async void OnAddPageClick(object sender, RoutedEventArgs e)
    {
        if (!ViewModel.CanAddPage) return;
        var dialog = new PageCreationDialog(ViewModel.LastPageAppearance) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        if (dialog.CreationMode == PageCreationMode.Page)
        {
            ViewModel.AddPage(dialog.PageTitle, dialog.PageAppearance);
            return;
        }

        var invert = ResolveInversion(ViewModel.PdfDisplayMode, "PDF 页面");
        if (invert == null) return;
        try
        {
            Mouse.OverrideCursor = Cursors.Wait;
            var pages = await PdfImportService.RenderAsync(dialog.PdfPath);
            var baseTitle = Path.GetFileNameWithoutExtension(dialog.PdfPath);
            foreach (var page in pages)
            {
                var title = pages.Count == 1 ? baseTitle : $"{baseTitle} {page.PageNumber}";
                ViewModel.AddPdfPage(title, page, dialog.PdfPath, invert.Value);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"PDF 页面导入失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            Mouse.OverrideCursor = null;
        }
    }

    private void OnBackgroundFormatClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement button) return;
        var menu = new ContextMenu();
        AddBackground(menu, "标准网格", CanvasBackgroundFormat.GridStandard);
        AddBackground(menu, "密集网格", CanvasBackgroundFormat.GridDense);
        AddBackground(menu, "宽网格", CanvasBackgroundFormat.GridWide);
        menu.Items.Add(new Separator());
        AddBackground(menu, "标准横线", CanvasBackgroundFormat.RuledStandard);
        AddBackground(menu, "窄横线", CanvasBackgroundFormat.RuledNarrow);
        AddBackground(menu, "宽横线", CanvasBackgroundFormat.RuledWide);
        AddBackground(menu, "标准双栏横线", CanvasBackgroundFormat.RuledTwoColumnStandard);
        AddBackground(menu, "窄双栏横线", CanvasBackgroundFormat.RuledTwoColumnNarrow);
        AddBackground(menu, "宽双栏横线", CanvasBackgroundFormat.RuledTwoColumnWide);
        menu.Items.Add(new Separator());
        AddBackground(menu, "标准点阵", CanvasBackgroundFormat.DotsStandard);
        AddBackground(menu, "密集点阵", CanvasBackgroundFormat.DotsDense);
        AddBackground(menu, "宽点阵", CanvasBackgroundFormat.DotsWide);
        menu.Items.Add(new Separator());
        AddBackground(menu, "空白", CanvasBackgroundFormat.Blank);
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void AddBackground(ContextMenu menu, string label, CanvasBackgroundFormat format)
    {
        var item = new MenuItem { Header = label, IsChecked = ViewModel.ActivePageAppearance.Background == format };
        item.Click += (_, _) => ViewModel.SetBackgroundFormat(format);
        menu.Items.Add(item);
    }

    private void OnPageColorClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        AddPageColor(menu, "白色", "#FAFAFA", "#1B1B1B");
        AddPageColor(menu, "米色", "#F3E9D2", "#302A21");
        AddPageColor(menu, "蓝色", "#E2EDF8", "#1D2B38");
        AddPageColor(menu, "绿色", "#E0F1E7", "#1E3028");
        AddPageColor(menu, "粉色", "#F4E2E8", "#352329");
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnPaperSizeClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        AddMenuItem(menu, "无界页面", () => ViewModel.SetPaperKind(PaperKind.Infinite));
        AddMenuItem(menu, "A4 页面", () => ViewModel.SetPaperKind(PaperKind.A4));
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnTogglePageTitleClick(object sender, RoutedEventArgs e) => ViewModel.TogglePageTitle();
    private void OnTogglePageThemeClick(object sender, RoutedEventArgs e) => ViewModel.TogglePageThemeCommand.Execute(null);
    private void OnEnterFullPageClick(object sender, RoutedEventArgs e) => ViewModel.IsFullPageMode = true;
    private void OnExitFullPageClick(object sender, RoutedEventArgs e) => ViewModel.IsFullPageMode = false;

    private void OnZoomPercentClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        foreach (var percent in ViewportState.ZoomPresets)
            AddMenuItem(menu, $"{percent}%", () => FocusedCanvas.SetZoom(percent / 100f));
        button.ContextMenu = menu;
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnSearchClick(object sender, RoutedEventArgs e)
    {
        SearchPanel.Visibility = SearchPanel.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        if (SearchPanel.Visibility == Visibility.Visible)
        {
            SearchBox.Focus();
            SearchBox.SelectAll();
        }
    }

    private void OnCloseSearchClick(object sender, RoutedEventArgs e) => SearchPanel.Visibility = Visibility.Collapsed;
    private void OnSearchResultClick(object sender, RoutedEventArgs e) => SearchPanel.Visibility = Visibility.Collapsed;

    private void AddPageColor(ContextMenu menu, string label, string light, string dark)
    {
        var item = new MenuItem { Header = label };
        item.Click += (_, _) => ViewModel.SetPageColor(ViewModel.ActivePageAppearance.Theme == PageTheme.Dark ? dark : light, ViewModel.ActivePageAppearance.Theme);
        menu.Items.Add(item);
    }

    private Point InsertPoint(double y = 100) => FocusedCanvas.Viewport.ViewToDocument(72, y);

    private void OnInsertTableClick(object sender, RoutedEventArgs e)
    {
        var point = InsertPoint();
        var table = PageObject.Table((float)point.X, (float)point.Y);
        table.Cells = new List<string> { "序号", "项目", "状态", "1", "", "", "2", "", "" };
        FocusedCanvas.AddObject(table);
    }

    private void OnInsertAttachmentClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择要嵌入的附件", Filter = "所有文件 (*.*)|*.*" };
        if (dialog.ShowDialog(this) == true) InsertAssetObject(dialog.FileName, PageObjectKind.Attachment, "application/octet-stream", false);
    }

    private void OnInsertImageClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择图片", Multiselect = true, Filter = "图片 (*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp)|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|所有文件 (*.*)|*.*" };
        if (dialog.ShowDialog(this) != true) return;
        var invert = ResolveInversion(ViewModel.ImageDisplayMode, "图片");
        if (invert == null) return;
        var y = 110d;
        foreach (var file in dialog.FileNames)
        {
            y += InsertImageFile(file, invert.Value, y) + 18;
        }
    }

    private async void OnInsertPdfPrintoutClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择 PDF", Filter = "PDF 文件 (*.pdf)|*.pdf" };
        if (dialog.ShowDialog(this) != true) return;
        var invert = ResolveInversion(ViewModel.PdfDisplayMode, "PDF 打印输出");
        if (invert == null) return;
        try
        {
            Mouse.OverrideCursor = Cursors.Wait;
            var sourceAsset = ViewModel.ImportAsset(dialog.FileName);
            var pages = await PdfImportService.RenderAsync(dialog.FileName);
            var point = InsertPoint(110);
            var y = (float)point.Y;
            foreach (var page in pages)
            {
                var asset = ViewModel.WriteAsset(".png", page.PngBytes);
                var width = 620f;
                var height = width * (float)(page.HeightPoints / Math.Max(1, page.WidthPoints));
                FocusedCanvas.AddObject(new PageObject
                {
                    Kind = PageObjectKind.PdfPrintout,
                    X = (float)point.X,
                    Y = y,
                    Width = width,
                    Height = height,
                    DisplayName = $"{Path.GetFileName(dialog.FileName)} · {page.PageNumber}/{page.PageCount}",
                    AssetName = asset,
                    MimeType = "image/png",
                    ViewInverted = invert.Value,
                    SourceAssetName = sourceAsset,
                    SourcePageNumber = page.PageNumber,
                });
                y += height + 24;
            }
        }
        catch (Exception ex) { MessageBox.Show(this, $"PDF 导入失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error); }
        finally { Mouse.OverrideCursor = null; }
    }

    private void OnInsertSpreadsheetClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "选择 Excel 工作簿", Filter = "Excel 工作簿 (*.xlsx)|*.xlsx" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var asset = ViewModel.ImportAsset(dialog.FileName);
            var info = new FileInfo(dialog.FileName);
            var point = InsertPoint(120);
            var item = PageObject.Asset(PageObjectKind.Spreadsheet, (float)point.X, (float)point.Y, asset, info.Name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", info.Length);
            item.Width = 560;
            item.Height = 260;
            item.PreviewRows = XlsxPreviewService.ReadFirstSheet(dialog.FileName);
            FocusedCanvas.AddObject(item);
        }
        catch (Exception ex) { MessageBox.Show(this, $"工作簿预览失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void OnInsertStickerClick(object sender, RoutedEventArgs e)
    {
        var point = InsertPoint(130);
        var asset = ViewModel.WriteAsset(".png", CreateStickerPng());
        var item = PageObject.Asset(PageObjectKind.Sticker, (float)point.X, (float)point.Y, asset, "星标贴纸", "image/png", 0);
        item.Width = item.Height = 120;
        FocusedCanvas.AddObject(item);
    }

    private void OnInsertLinkClick(object sender, RoutedEventArgs e)
    {
        var currentId = ViewModel.FocusedDocument?.PageId;
        var target = ViewModel.Library.Notebooks
            .SelectMany(notebook => notebook.FlattenAllSections().SelectMany(section => section.Pages.Select(page => (notebook, section, page))))
            .FirstOrDefault(entry => entry.page.Id != currentId);
        if (target.page == null)
        {
            MessageBox.Show(this, "至少再创建一个页面后才能插入笔记链接。", "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        var point = InsertPoint(140);
        FocusedCanvas.AddObject(new PageObject
        {
            Kind = PageObjectKind.NoteLink,
            X = (float)point.X,
            Y = (float)point.Y,
            Width = 300,
            Height = 38,
            DisplayName = target.page.Title,
            TargetNotebookId = target.notebook.Id,
            TargetSectionId = target.section.Id,
            TargetPageId = target.page.Id,
        });
    }

    private void InsertAssetObject(string path, PageObjectKind kind, string mime, bool inverted, double y = 100)
    {
        var asset = ViewModel.ImportAsset(path);
        var info = new FileInfo(path);
        var point = InsertPoint(y);
        var item = PageObject.Asset(kind, (float)point.X, (float)point.Y, asset, info.Name, mime, info.Length);
        item.ViewInverted = inverted;
        FocusedCanvas.AddObject(item);
    }

    private double InsertImageFile(string path, bool inverted, double y, Point? insertionPoint = null)
    {
        var asset = ViewModel.ImportAsset(path);
        var info = new FileInfo(path);
        var point = insertionPoint ?? InsertPoint(y);
        var item = PageObject.Asset(PageObjectKind.Image, (float)point.X, (float)point.Y, asset, info.Name, MimeFor(path), info.Length);
        try
        {
            using var stream = File.OpenRead(path);
            var frame = BitmapFrame.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            ApplyFittedImageSize(item, frame.PixelWidth, frame.PixelHeight);
        }
        catch
        {
            // The existing asset renderer will surface unsupported image formats without losing the source file.
        }
        item.ViewInverted = inverted;
        FocusedCanvas.AddObject(item);
        return item.Height;
    }

    private void OnCopyOptionsClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement anchor) return;
        ShowChoiceMenu(anchor, new (string, Action)[] {
            ("复制（原格式）", () => FocusedCanvas.CopySelection()),
            ("复制为 SVG", () => FocusedCanvas.CopySelectionImage(true)),
            ("复制为 PNG", () => FocusedCanvas.CopySelectionImage(false)),
        });
    }

    private void PasteHomeContent()
    {
        if (FocusedCanvas.PasteSelection()) return;
        if (TryPasteSystemImages()) return;
        if (_clipboardTextBox is { IsLoaded: true } textBox && SystemClipboardContainsText())
        {
            textBox.Paste();
            return;
        }
    }

    private bool TryPasteSystemImages()
    {
        if (FocusedCanvas.Document == null) return false;
        try
        {
            var data = Clipboard.GetDataObject();
            if (data == null) return false;
            var bitmap = data.GetDataPresent(DataFormats.Bitmap, true)
                ? Clipboard.GetImage() ?? data.GetData(DataFormats.Bitmap, true) as BitmapSource
                : null;
            var paths = data.GetDataPresent(DataFormats.FileDrop, true) && data.GetData(DataFormats.FileDrop, true) is string[] dropped
                ? dropped.Where(IsSupportedImagePath).ToArray()
                : Array.Empty<string>();
            if (bitmap == null && paths.Length == 0) return false;

            var invert = ResolveInversion(ViewModel.ImageDisplayMode, "剪贴板图片");
            if (invert == null) return true;
            if (bitmap != null)
            {
                var bytes = BitmapToPng(bitmap);
                var asset = ViewModel.WriteAsset(".png", bytes);
                var point = FocusedCanvas.PasteInsertionPoint;
                var item = PageObject.Asset(PageObjectKind.Image, (float)point.X, (float)point.Y, asset, "剪贴板图片.png", "image/png", bytes.Length);
                ApplyFittedImageSize(item, bitmap.PixelWidth, bitmap.PixelHeight);
                item.ViewInverted = invert.Value;
                FocusedCanvas.AddObject(item);
                return true;
            }

            var anchor = FocusedCanvas.PasteInsertionPoint;
            var y = 0d;
            foreach (var path in paths) y += InsertImageFile(path, invert.Value, y, new Point(anchor.X, anchor.Y + y)) + 18;
            return true;
        }
        catch (ExternalException ex)
        {
            MessageBox.Show(this, $"剪贴板暂时无法读取：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Warning);
            return true;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"剪贴板图片粘贴失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error);
            return true;
        }
    }

    private static void ApplyFittedImageSize(PageObject item, int pixelWidth, int pixelHeight)
    {
        var size = ImagePlacement.FitPixels(pixelWidth, pixelHeight);
        item.Width = (float)size.Width;
        item.Height = (float)size.Height;
    }

    private static bool IsSupportedImagePath(string path) =>
        File.Exists(path) && Path.GetExtension(path).ToLowerInvariant() is ".png" or ".jpg" or ".jpeg" or ".bmp" or ".gif" or ".webp";

    private static bool SystemClipboardContainsText()
    {
        try { return Clipboard.ContainsText(); }
        catch (ExternalException) { return false; }
    }

    private bool? ResolveInversion(ImageImportDisplayMode mode, string kind)
    {
        if (ImageImportPolicy.Resolve(mode, ViewModel.IsDarkPageBackground) is { } resolved) return resolved;
        var result = MessageBox.Show(this, $"{kind}在深色页面中反色显示吗？", "墨笺", MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        return result == MessageBoxResult.Cancel ? null : result == MessageBoxResult.Yes;
    }

    private void OnHomeCommandClick(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement button || !Enum.TryParse<HomeCommand>(button.Tag?.ToString(), out var command)) return;
        switch (command)
        {
            case HomeCommand.Paste: PasteHomeContent(); break;
            case HomeCommand.Cut: if (_clipboardTextBox is { IsLoaded: true } cutBox) cutBox.Cut(); else FocusedCanvas.CopySelection(cut: true); break;
            case HomeCommand.Copy: if (_clipboardTextBox is { IsLoaded: true } copyBox) copyBox.Copy(); else FocusedCanvas.CopySelection(); break;
            case HomeCommand.CopySvg: FocusedCanvas.CopySelectionImage(true); break;
            case HomeCommand.CopyPng: FocusedCanvas.CopySelectionImage(false); break;
            case HomeCommand.FormatPainter: ApplyFormatPainter(); break;
            case HomeCommand.FontFamily:
                ShowChoiceMenu(button, new[]
                {
                    ("无衬线", (Action)(() => ApplyTextFormat(item => item.FontFamily = "Segoe UI Variable, Microsoft YaHei UI"))),
                    ("衬线", (Action)(() => ApplyTextFormat(item => item.FontFamily = "Georgia, SimSun"))),
                    ("等宽", (Action)(() => ApplyTextFormat(item => item.FontFamily = "Cascadia Mono, Consolas"))),
                });
                break;
            case HomeCommand.FontSize:
                ShowChoiceMenu(button, new[] { 10, 12, 14, 16, 18, 24, 32 }.Select(size => ($"{size}", (Action)(() => ApplyTextFormat(item => item.FontSize = size))))); break;
            case HomeCommand.Bold: ApplyTextFormat(item => item.Bold = !item.Bold); break;
            case HomeCommand.Italic: ApplyTextFormat(item => item.Italic = !item.Italic); break;
            case HomeCommand.Underline: ApplyTextFormat(item => item.Underline = !item.Underline); break;
            case HomeCommand.Strikethrough: ApplyTextFormat(item => item.Strikethrough = !item.Strikethrough); break;
            case HomeCommand.Subscript: ApplyTextFormat(item => item.Subscript = !item.Subscript); break;
            case HomeCommand.FontColor:
                ShowChoiceMenu(button, new[]
                {
                    ("白色", (Action)(() => ApplyTextFormat(item => item.ForegroundHex = "#F4F4F4"))),
                    ("红色", (Action)(() => ApplyTextFormat(item => item.ForegroundHex = "#E83139"))),
                    ("蓝色", (Action)(() => ApplyTextFormat(item => item.ForegroundHex = "#569CD6"))),
                    ("黄色", (Action)(() => ApplyTextFormat(item => item.ForegroundHex = "#FFD60A"))),
                });
                break;
            case HomeCommand.Highlight:
                ShowChoiceMenu(button, new[]
                {
                    ("黄色", (Action)(() => ApplyTextFormat(item => item.BackgroundHex = "#685800"))),
                    ("绿色", (Action)(() => ApplyTextFormat(item => item.BackgroundHex = "#185C36"))),
                    ("红色", (Action)(() => ApplyTextFormat(item => item.BackgroundHex = "#6F252A"))),
                    ("无", (Action)(() => ApplyTextFormat(item => item.BackgroundHex = "Transparent"))),
                });
                break;
            case HomeCommand.ClearFormatting:
                ApplyTextFormat(item =>
                {
                    item.FontFamily = "Segoe UI Variable, Microsoft YaHei UI"; item.FontSize = 16; item.Bold = false; item.Italic = false;
                    item.Underline = false; item.Strikethrough = false; item.Subscript = false; item.ForegroundHex = string.Empty;
                    item.BackgroundHex = "Transparent"; item.IndentLevel = 0; item.Alignment = "Left"; item.LineSpacing = 1; item.StyleName = "正文";
                });
                break;
            case HomeCommand.Bullets: TransformSelectedText(AddBullets); break;
            case HomeCommand.Numbering: TransformSelectedText(AddNumbering); break;
            case HomeCommand.IndentDecrease: ApplyTextFormat(item => item.IndentLevel = Math.Max(0, item.IndentLevel - 1)); break;
            case HomeCommand.IndentIncrease: ApplyTextFormat(item => item.IndentLevel++); break;
            case HomeCommand.AlignLeft: ApplyTextFormat(item => item.Alignment = "Left"); break;
            case HomeCommand.AlignCenter: ApplyTextFormat(item => item.Alignment = "Center"); break;
            case HomeCommand.AlignRight: ApplyTextFormat(item => item.Alignment = "Right"); break;
            case HomeCommand.LineSpacing:
                ShowChoiceMenu(button, new[] { 1d, 1.15d, 1.5d, 2d }.Select(spacing => ($"{spacing:0.##} 倍", (Action)(() => ApplyTextFormat(item => item.LineSpacing = spacing))))); break;
            case HomeCommand.Tag:
                ShowChoiceMenu(button, new[]
                {
                    ("重要", (Action)(() => ViewModel.ArmTagPlacementCommand.Execute(CanvasTagKind.Important))),
                    ("问题", (Action)(() => ViewModel.ArmTagPlacementCommand.Execute(CanvasTagKind.Question))),
                });
                break;
            case HomeCommand.FindTags: ShowTagResults(button); break;
            case HomeCommand.Styles:
                ShowChoiceMenu(button, new[] { "正文", "标题", "标题 1", "标题 2" }.Select(style => (style, (Action)(() => ApplyNamedStyle(style))))); break;
        }
    }

    private TextBox? FocusedTextBox()
    {
        if (Keyboard.FocusedElement is TextBox current && FindAncestor<NoteCanvas>(current) != null) _lastTextBox = current;
        return _lastTextBox?.IsLoaded == true ? _lastTextBox : null;
    }

    private void ApplyTextFormat(Action<TextItem> update)
    {
        if (FocusedTextBox() is not { Tag: TextItem item } box) return;
        FindAncestor<NoteCanvas>(box)?.UpdateTextItem(item, update);
    }

    private void ApplyFormatPainter()
    {
        if (FocusedTextBox() is not { Tag: TextItem item }) return;
        if (_formatPainter == null) _formatPainter = TextStyleSnapshot.Capture(item);
        else
        {
            var snapshot = _formatPainter;
            ApplyTextFormat(snapshot.Apply);
            _formatPainter = null;
        }
    }

    private void TransformSelectedText(Func<string, string> transform)
    {
        var box = FocusedTextBox();
        if (box == null) return;
        if (box.SelectionLength > 0)
        {
            var start = box.SelectionStart;
            var replacement = transform(box.SelectedText);
            box.SelectedText = replacement;
            box.Select(start, replacement.Length);
        }
        else box.Text = transform(box.Text);
    }

    private static string AddBullets(string source) => string.Join(Environment.NewLine, source.ReplaceLineEndings("\n").Split('\n').Select(line => $"• {line.TrimStart('•', ' ')}"));
    private static string AddNumbering(string source) => string.Join(Environment.NewLine, source.ReplaceLineEndings("\n").Split('\n').Select((line, index) => $"{index + 1}. {line.TrimStart()}"));

    private void ApplyNamedStyle(string style)
    {
        ApplyTextFormat(item =>
        {
            item.StyleName = style;
            (item.FontSize, item.Bold) = style switch { "标题" => (32, true), "标题 1" => (24, true), "标题 2" => (18, true), _ => (16, false) };
        });
    }

    private static void ShowChoiceMenu(FrameworkElement anchor, IEnumerable<(string Label, Action Action)> choices)
    {
        var menu = new ContextMenu();
        foreach (var (label, action) in choices) AddMenuItem(menu, label, action);
        menu.PlacementTarget = anchor;
        menu.IsOpen = true;
    }

    private void ShowTagResults(FrameworkElement anchor)
    {
        var tags = FocusedCanvas.Document?.Tags ?? new List<CanvasTag>();
        if (tags.Count == 0) return;
        ShowChoiceMenu(anchor, tags.Select((tag, index) => ($"{tag.Kind.GetLabel()} · {index + 1}", (Action)(() => FocusedCanvas.CenterOnDocumentPoint(tag.DocumentX, tag.DocumentY)))));
    }

    private static T? FindAncestor<T>(DependencyObject source) where T : DependencyObject
    {
        for (var current = source; current != null; current = VisualTreeHelper.GetParent(current))
            if (current is T match) return match;
        return null;
    }

    private void OnClearPageClick(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(this, "清除当前页的全部笔迹、文字和插入内容？", "墨笺", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes) FocusedCanvas.ClearPage();
    }

    private void OnCloseToolFlyoutClick(object sender, RoutedEventArgs e) => ViewModel.IsToolFlyoutOpen = false;

    private void OnSetToolColorClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button button && Enum.TryParse<InkPalette>(button.Tag?.ToString(), out var color)) ViewModel.SetSelectedToolColor(color);
    }

    private void OnSetLassoShapeClick(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleButton button && Enum.TryParse<LassoShape>(button.Tag?.ToString(), out var shape))
            ViewModel.SetSelectedLassoShape(shape);
    }

    private void OnToolSettingChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        ViewModel.SaveToolSettings();
        LeftCanvas.InvalidateVisual();
        RightCanvas.InvalidateVisual();
    }

    private void OnZoomOutClick(object sender, RoutedEventArgs e) => FocusedCanvas.SetZoom(FocusedCanvas.Viewport.Scale / 1.25f);
    private void OnZoomInClick(object sender, RoutedEventArgs e) => FocusedCanvas.SetZoom(FocusedCanvas.Viewport.Scale * 1.25f);
    private void OnResetZoomClick(object sender, RoutedEventArgs e) => FocusedCanvas.SetZoom(1f);
    private void UpdateZoomStatus() => ZoomStatus.Text = $"{MathF.Round(FocusedCanvas.Viewport.Scale * 100f)}%";
    private void OnLeftCanvasFocus(object sender, MouseButtonEventArgs e) { ViewModel.FocusCanvas(CanvasSlot.Left); UpdateZoomStatus(); }
    private void OnRightCanvasFocus(object sender, MouseButtonEventArgs e) { ViewModel.FocusCanvas(CanvasSlot.Right); UpdateZoomStatus(); }

    private void OnWindowPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (FocusedTextBox() == null && DirectInputPolicy.IsSelectionDeleteKey(e.Key, Keyboard.Modifiers) && FocusedCanvas.DeleteSelection())
        {
            e.Handled = true;
            return;
        }
        var gesture = ShortcutGesture.FromEvent(e);
        var shortcutAction = ViewModel.FindShortcutAction(gesture);
        if (shortcutAction != null && ExecuteShortcutAction(shortcutAction))
        {
            e.Handled = true;
            return;
        }
        if (e.Key == Key.Escape && SearchPanel.Visibility == Visibility.Visible) { SearchPanel.Visibility = Visibility.Collapsed; e.Handled = true; }
        else if (e.Key == Key.Escape && ViewModel.IsBackstageOpen) { ViewModel.CloseBackstageCommand.Execute(null); e.Handled = true; }
    }

    private void ConfigureShortcut(ShortcutActionDescriptor action)
    {
        var prompt = new TextBlock
        {
            Text = $"按下要绑定到“{action.Label}”的按键组合\n\nEsc 取消；重复组合会从原操作移除。",
            Foreground = new SolidColorBrush(Color.FromRgb(244, 244, 244)),
            FontSize = 16,
            TextAlignment = TextAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(28),
        };
        var dialog = new Window
        {
            Title = "设置快捷键",
            Owner = this,
            Width = 430,
            Height = 210,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = new SolidColorBrush(Color.FromRgb(27, 27, 27)),
            Content = prompt,
        };
        ShortcutGesture? captured = null;
        dialog.PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) { dialog.DialogResult = false; e.Handled = true; return; }
            var candidate = ShortcutGesture.FromEvent(e);
            if (!candidate.IsValid) { e.Handled = true; return; }
            captured = candidate;
            dialog.DialogResult = true;
            e.Handled = true;
        };
        dialog.Loaded += (_, _) => { dialog.Activate(); Keyboard.Focus(dialog); };
        if (dialog.ShowDialog() == true && captured.HasValue) ViewModel.SetShortcut(action.Id, captured.Value);
    }

    private bool ExecuteShortcutAction(string actionId)
    {
        if (actionId.StartsWith("tool:", StringComparison.Ordinal)) return ViewModel.SelectToolImmediate(actionId[5..]);
        if (actionId.StartsWith("home:", StringComparison.Ordinal))
        {
            if (actionId == "home:tag-important") { ViewModel.PendingTagKind = CanvasTagKind.Important; return true; }
            if (actionId == "home:tag-question") { ViewModel.PendingTagKind = CanvasTagKind.Question; return true; }
            if (actionId == "home:find-tags") { ShowTagResults(FocusedCanvas); return true; }
            var id = actionId[5..];
            var command = Enum.GetValues<HomeCommand>().FirstOrDefault(item => item.GetId() == id);
            if (command.GetId() != id) return false;
            OnHomeCommandClick(new Button { Tag = command.ToString() }, new RoutedEventArgs());
            return true;
        }
        switch (actionId)
        {
            case "file:backstage": ViewModel.OpenBackstageCommand.Execute(null); return true;
            case "file:save": ViewModel.SaveAll(); return true;
            case "file:export-page-pdf": ExportCurrentPagePdf(); return true;
            case "file:export-notebook": ExportNotebookPackage(); return true;
            case "insert:table": OnInsertTableClick(this, new RoutedEventArgs()); return true;
            case "insert:attachment": OnInsertAttachmentClick(this, new RoutedEventArgs()); return true;
            case "insert:pdf": OnInsertPdfPrintoutClick(this, new RoutedEventArgs()); return true;
            case "insert:spreadsheet": OnInsertSpreadsheetClick(this, new RoutedEventArgs()); return true;
            case "insert:image": OnInsertImageClick(this, new RoutedEventArgs()); return true;
            case "insert:sticker": OnInsertStickerClick(this, new RoutedEventArgs()); return true;
            case "insert:link": OnInsertLinkClick(this, new RoutedEventArgs()); return true;
            case "draw:undo": FocusedCanvas.Undo(); return true;
            case "draw:redo": FocusedCanvas.Redo(); return true;
            case "draw:previous-tool": return ViewModel.CycleDrawingTool(-1);
            case "draw:next-tool": return ViewModel.CycleDrawingTool(1);
            case "draw:pan": ViewModel.SingleFingerPanEnabled = !ViewModel.SingleFingerPanEnabled; return true;
            case "draw:insert-space": ViewModel.InsertSpaceModeEnabled = !ViewModel.InsertSpaceModeEnabled; return true;
            case "draw:delete-selection": if (FocusedTextBox() != null) return false; FocusedCanvas.DeleteSelection(); return true;
            case "draw:clear": OnClearPageClick(this, new RoutedEventArgs()); return true;
            case "draw:background-blank": ViewModel.SetBackgroundFormat(CanvasBackgroundFormat.Blank); return true;
            case "draw:background-grid": ViewModel.SetBackgroundFormat(CanvasBackgroundFormat.GridStandard); return true;
            case "draw:background-ruled": ViewModel.SetBackgroundFormat(CanvasBackgroundFormat.RuledStandard); return true;
            case "draw:background-dots": ViewModel.SetBackgroundFormat(CanvasBackgroundFormat.DotsStandard); return true;
            case "view:search": SearchPanel.Visibility = Visibility.Visible; SearchBox.Focus(); SearchBox.SelectAll(); return true;
            case "view:normal-page": ViewModel.IsFullPageMode = false; return true;
            case "view:full-page": ViewModel.IsFullPageMode = true; return true;
            case "view:split": ViewModel.ToggleSplitViewCommand.Execute(null); return true;
            case "view:zoom-in": FocusedCanvas.SetZoom(FocusedCanvas.Viewport.Scale * 1.25f); return true;
            case "view:zoom-out": FocusedCanvas.SetZoom(FocusedCanvas.Viewport.Scale / 1.25f); return true;
            case "view:zoom-reset": FocusedCanvas.SetZoom(1f); return true;
            case "view:fit-width": FocusedCanvas.FitPageWidth(); return true;
            case "view:toggle-title": ViewModel.TogglePageTitle(); return true;
            case "view:toggle-theme": ViewModel.TogglePageTheme(); return true;
            case "view:previous-page": return ViewModel.CyclePage(-1);
            case "view:next-page": return ViewModel.CyclePage(1);
            default: return false;
        }
    }

    private void OnWindowClosing(object? sender, CancelEventArgs e)
    {
        try { ViewModel.SaveAll(); }
        catch (Exception error)
        {
            e.Cancel = true;
            MessageBox.Show(this, $"笔记保存失败，窗口将保持打开。请重试保存。\n{error.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void NewNotebook()
    {
        var title = Prompt("新建笔记本", "笔记本名称", "新笔记本");
        if (title == null) return;
        ViewModel.AddNotebook(title);
        ViewModel.CloseBackstageCommand.Execute(null);
    }

    private void OpenWorkspaceFolder() => Process.Start(new ProcessStartInfo(ViewModel.WorkspaceRoot) { UseShellExecute = true });

    private void SwitchWorkspace()
    {
        var dialog = new OpenFolderDialog { Title = "选择墨笺工作区", Multiselect = false };
        if (dialog.ShowDialog(this) != true) return;
        ViewModel.SwitchWorkspace(dialog.FolderName);
        ViewModel.CloseBackstageCommand.Execute(null);
    }

    private void ExportCurrentPagePdf()
    {
        RunExport(new ExportOptions(), false, false);
    }

    private void ExportNotebookPackage()
    {
        var dialog = new SaveFileDialog { Title = "导出墨笺笔记本", Filter = "墨笺笔记本 (*.mjn)|*.mjn", FileName = $"{ViewModel.ActiveNotebookTitle}.mjn", AddExtension = true, DefaultExt = ".mjn" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            ViewModel.SaveAll();
            var layers = ViewModel.FocusedDocument == null ? null : new Dictionary<string, byte[]>
            {
                ["页面背景"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Background)),
                ["插入内容"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Inserts)),
                ["墨迹与文字"] = BitmapToPng(FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.InkAndText)),
            };
            NotebookPackageService.Export(dialog.FileName, ViewModel.ActiveWorkspace, ViewModel.Store, ViewModel.FocusedDocument?.PageId, layers);
            MessageBox.Show(this, "笔记本包已导出。", "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) { MessageBox.Show(this, $"笔记本包导出失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private static byte[] BitmapToPng(BitmapSource bitmap)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    private static byte[] CreateStickerPng()
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            var points = new PointCollection();
            for (var index = 0; index < 10; index++)
            {
                var angle = -Math.PI / 2 + index * Math.PI / 5;
                var radius = index % 2 == 0 ? 52 : 23;
                points.Add(new Point(64 + Math.Cos(angle) * radius, 64 + Math.Sin(angle) * radius));
            }
            var geometry = new StreamGeometry();
            using var context = geometry.Open();
            context.BeginFigure(points[0], true, true);
            context.PolyLineTo(points.Skip(1).ToList(), true, true);
            dc.DrawGeometry(new SolidColorBrush(Color.FromRgb(218, 178, 233)), new Pen(Brushes.White, 3), geometry);
        }
        var bitmap = new RenderTargetBitmap(128, 128, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        return BitmapToPng(bitmap);
    }

    private static string MimeFor(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".webp" => "image/webp",
        ".gif" => "image/gif",
        ".bmp" => "image/bmp",
        _ => "image/png",
    };

    private static MenuItem AddMenuItem(ItemsControl menu, string label, Action action)
    {
        var item = new MenuItem { Header = label };
        item.Click += (_, _) => action();
        menu.Items.Add(item);
        return item;
    }

    private string? Prompt(string title, string label, string initial)
    {
        var dialog = new Window
        {
            Owner = this,
            Title = title,
            Width = 390,
            Height = 170,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            Background = new SolidColorBrush(Color.FromRgb(20, 20, 20)),
        };
        var panel = new DockPanel { Margin = new Thickness(18) };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        DockPanel.SetDock(buttons, Dock.Bottom);
        var input = new TextBox { Text = initial, Margin = new Thickness(0, 8, 0, 16), Padding = new Thickness(8, 5, 8, 5), Background = new SolidColorBrush(Color.FromRgb(42, 42, 42)), Foreground = Brushes.White, BorderBrush = new SolidColorBrush(Color.FromRgb(61, 61, 61)) };
        var cancel = new Button { Content = "取消", Width = 74, Height = 30, Margin = new Thickness(0, 0, 8, 0), IsCancel = true };
        var ok = new Button { Content = "确定", Width = 74, Height = 30, IsDefault = true };
        ok.Click += (_, _) => dialog.DialogResult = true;
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        var content = new StackPanel();
        content.Children.Add(new TextBlock { Text = label, Foreground = Brushes.White });
        content.Children.Add(input);
        panel.Children.Add(buttons);
        panel.Children.Add(content);
        dialog.Content = panel;
        input.Loaded += (_, _) => { input.Focus(); input.SelectAll(); };
        return dialog.ShowDialog() == true ? input.Text.Trim() : null;
    }

    private string? PromptMultiline(string title, string label, string initial)
    {
        var dialog = new Window
        {
            Owner = this,
            Title = title,
            Width = 620,
            Height = 430,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = new SolidColorBrush(Color.FromRgb(20, 20, 20)),
        };
        var root = new DockPanel { Margin = new Thickness(18) };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        DockPanel.SetDock(buttons, Dock.Bottom);
        var cancel = new Button { Content = "取消", Width = 74, Height = 30, Margin = new Thickness(0, 10, 8, 0), IsCancel = true };
        var ok = new Button { Content = "确定", Width = 74, Height = 30, Margin = new Thickness(0, 10, 0, 0), IsDefault = true };
        ok.Click += (_, _) => dialog.DialogResult = true;
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        root.Children.Add(buttons);
        var content = new DockPanel();
        var description = new TextBlock { Text = label, Foreground = Brushes.White, Margin = new Thickness(0, 0, 0, 8) };
        DockPanel.SetDock(description, Dock.Top);
        var input = new TextBox
        {
            Text = initial,
            AcceptsReturn = true,
            AcceptsTab = true,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            Background = new SolidColorBrush(Color.FromRgb(42, 42, 42)),
            Foreground = Brushes.White,
            BorderBrush = new SolidColorBrush(Color.FromRgb(61, 61, 61)),
            Padding = new Thickness(8),
        };
        content.Children.Add(description);
        content.Children.Add(input);
        root.Children.Add(content);
        dialog.Content = root;
        input.Loaded += (_, _) => { input.Focus(); input.SelectAll(); };
        return dialog.ShowDialog() == true ? input.Text : null;
    }

    private sealed record TextStyleSnapshot(
        string FontFamily,
        double FontSize,
        bool Bold,
        bool Italic,
        bool Underline,
        bool Strikethrough,
        bool Subscript,
        string ForegroundHex,
        string BackgroundHex,
        int IndentLevel,
        string Alignment,
        double LineSpacing,
        string StyleName)
    {
        public static TextStyleSnapshot Capture(TextItem item) => new(item.FontFamily, item.FontSize, item.Bold, item.Italic, item.Underline,
            item.Strikethrough, item.Subscript, item.ForegroundHex, item.BackgroundHex, item.IndentLevel, item.Alignment, item.LineSpacing, item.StyleName);

        public void Apply(TextItem item)
        {
            item.FontFamily = FontFamily; item.FontSize = FontSize; item.Bold = Bold; item.Italic = Italic; item.Underline = Underline;
            item.Strikethrough = Strikethrough; item.Subscript = Subscript; item.ForegroundHex = ForegroundHex; item.BackgroundHex = BackgroundHex;
            item.IndentLevel = IndentLevel; item.Alignment = Alignment; item.LineSpacing = LineSpacing; item.StyleName = StyleName;
        }
    }

    private sealed record PageNavigationClipboard(string NotebookId, string SectionId, string PageId, bool IsCut);
}
