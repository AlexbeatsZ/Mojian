using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
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
    private TextStyleSnapshot? _formatPainter;
    private PageNavigationClipboard? _pageNavigationClipboard;
    private MainViewModel ViewModel => (MainViewModel)DataContext;
    private NoteCanvas FocusedCanvas => ViewModel.FocusedSlot == CanvasSlot.Right ? RightCanvas : LeftCanvas;

    public MainWindow()
    {
        DataContext = new MainViewModel();
        InitializeComponent();
        Loaded += OnLoaded;
        SourceInitialized += OnSourceInitialized;
        PreviewGotKeyboardFocus += (_, e) =>
        {
            if (e.NewFocus is TextBox box && FindAncestor<NoteCanvas>(box) != null) _lastTextBox = box;
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
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int valueSize);

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_eventsWired) return;
        _eventsWired = true;
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
        Backstage.ConfigureShortcutRequested += ConfigureShortcut;
        Backstage.ClearShortcutRequested += action => ViewModel.ClearShortcut(action.Id);
        UpdateZoomStatus();
    }

    private void ConfigureCanvas(NoteCanvas canvas)
    {
        canvas.ResolveAssetPath = name => canvas.Document == null ? string.Empty : ViewModel.ResolveAsset(canvas.Document, name);
        canvas.DocumentChanged += (_, _) => ViewModel.SaveDocument(canvas.Document);
        canvas.ViewportChanged += (_, _) => { if (canvas == FocusedCanvas) UpdateZoomStatus(); };
        canvas.ObjectActivated += OnObjectActivated;
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
        menu.IsOpen = true;
    }

    private void OnAddToolMenuClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        AddMenuItem(menu, "添加钢笔", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Pen));
        AddMenuItem(menu, "添加荧光笔", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Highlighter));
        AddMenuItem(menu, "添加橡皮", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Eraser));
        AddMenuItem(menu, "添加框选", () => ViewModel.AddToolCommand.Execute(DrawingToolKind.Lasso));
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void OnFullPageToolClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.Tag is not string id) return;
        if (id.StartsWith("tool:", StringComparison.Ordinal))
        {
            ViewModel.SelectToolCommand.Execute(id[5..]);
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
            case "draw:cursor": ViewModel.TextModeEnabled = !ViewModel.TextModeEnabled; break;
            case "draw:add": OnAddToolMenuClick(button, e); break;
            case "draw:pan": ViewModel.SingleFingerPanEnabled = !ViewModel.SingleFingerPanEnabled; break;
            case "draw:insert_space": ViewModel.InsertSpaceModeEnabled = !ViewModel.InsertSpaceModeEnabled; break;
            case "draw:rule_lines": OnBackgroundFormatClick(button, e); break;
            case "draw:clear": OnClearPageClick(button, e); break;
        }
    }

    private void OnAddPageClick(object sender, RoutedEventArgs e)
    {
        if (!ViewModel.CanAddPage) return;
        var title = Prompt("添加页面", "页面标题", "无标题页");
        if (title != null) ViewModel.AddPage(title, ViewModel.ActivePageAppearance);
    }

    private void OnBackgroundFormatClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button) return;
        var menu = new ContextMenu();
        AddBackground(menu, "标准网格", CanvasBackgroundFormat.GridStandard);
        AddBackground(menu, "密集网格", CanvasBackgroundFormat.GridDense);
        AddBackground(menu, "宽网格", CanvasBackgroundFormat.GridWide);
        menu.Items.Add(new Separator());
        AddBackground(menu, "标准横线", CanvasBackgroundFormat.RuledStandard);
        AddBackground(menu, "窄横线", CanvasBackgroundFormat.RuledNarrow);
        AddBackground(menu, "宽横线", CanvasBackgroundFormat.RuledWide);
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
        foreach (var percent in new[] { 50, 75, 100, 125, 150, 200, 400, 800 })
            AddMenuItem(menu, $"{percent}%", () => FocusedCanvas.SetZoom(percent / 100f));
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
            InsertAssetObject(file, PageObjectKind.Image, MimeFor(file), invert.Value, y);
            y += 28;
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

    private bool? ResolveInversion(ImageImportDisplayMode mode, string kind)
    {
        if (mode == ImageImportDisplayMode.Invert) return true;
        if (mode == ImageImportDisplayMode.Original) return false;
        var result = MessageBox.Show(this, $"{kind}在深色页面中自动反色显示吗？\n\n导出仍使用原始像素。", "墨笺", MessageBoxButton.YesNoCancel, MessageBoxImage.Question);
        return result == MessageBoxResult.Cancel ? null : result == MessageBoxResult.Yes;
    }

    private void OnHomeCommandClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || !Enum.TryParse<HomeCommand>(button.Tag?.ToString(), out var command)) return;
        if (command is not HomeCommand.Tag and not HomeCommand.FindTags) ViewModel.TextModeEnabled = true;
        switch (command)
        {
            case HomeCommand.Paste: FocusedTextBox()?.Paste(); break;
            case HomeCommand.Cut: FocusedTextBox()?.Cut(); break;
            case HomeCommand.Copy: FocusedTextBox()?.Copy(); break;
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

    private void OnDeleteSelectionClick(object sender, RoutedEventArgs e) => FocusedCanvas.DeleteSelection();

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
    private void UpdateZoomStatus() => ZoomStatus.Text = $"{FocusedCanvas.Viewport.Scale:P0}";
    private void OnLeftCanvasFocus(object sender, MouseButtonEventArgs e) { ViewModel.FocusCanvas(CanvasSlot.Left); UpdateZoomStatus(); }
    private void OnRightCanvasFocus(object sender, MouseButtonEventArgs e) { ViewModel.FocusCanvas(CanvasSlot.Right); UpdateZoomStatus(); }

    private void OnWindowPreviewKeyDown(object sender, KeyEventArgs e)
    {
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
            case "draw:cursor": ViewModel.TextModeEnabled = !ViewModel.TextModeEnabled; return true;
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

    private void OnWindowClosing(object? sender, CancelEventArgs e) => ViewModel.SaveAll();

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
        if (ViewModel.FocusedDocument == null) return;
        var dialog = new SaveFileDialog { Title = "导出当前页面", Filter = "PDF 文件 (*.pdf)|*.pdf", FileName = $"{ViewModel.ActivePageTitle}.pdf", AddExtension = true, DefaultExt = ".pdf" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            LayeredPdfExportService.Write(dialog.FileName, new[]
            {
                new PdfLayer("页面背景", FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Background)),
                new PdfLayer("插入内容", FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.Inserts)),
                new PdfLayer("墨迹与文字", FocusedCanvas.RenderLayerBitmap(CanvasRenderLayer.InkAndText)),
            });
            MessageBox.Show(this, "PDF 已导出。", "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) { MessageBox.Show(this, $"PDF 导出失败：{ex.Message}", "墨笺", MessageBoxButton.OK, MessageBoxImage.Error); }
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
