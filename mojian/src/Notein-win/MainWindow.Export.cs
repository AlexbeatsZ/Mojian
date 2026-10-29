using System.IO;
using System.Printing;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win;

public partial class MainWindow
{
    private readonly Dictionary<string, PageDocument> _exportDocuments = [];

    private void ConfigureExportPanels()
    {
        Backstage.ExportSettings.Describe = DescribeExport;
        Backstage.PrintSettings.Describe = DescribeExport;
        Backstage.PrintSettings.RestoreOptions(ViewModel.LastPrintOptions);
        Backstage.PrintSettings.OptionsChanged += ViewModel.RememberPrintOptions;
        Backstage.PrintSettings.PrintImages = options =>
        {
            var pages = ExportPages(options.Scope).Where(p => ViewModel.DisplayAppearance(p.Appearance).Theme == PageTheme.Dark).ToArray();
            return (pages.Length > 0, pages.SelectMany(page => ExportDocument(page).Objects
                .Where(o => o.Kind is PageObjectKind.Image or PageObjectKind.Sticker or PageObjectKind.PdfPrintout)
                .Select((o, i) => new ExportImageChoice(o.Id, $"{page.Title} · 图片 {i + 1} · {o.DisplayName}", o.ViewInverted))).ToArray());
        };
        Backstage.ExportSettings.Requested += RunExport;
        Backstage.PrintSettings.Requested += RunExport;
        Backstage.IsVisibleChanged += (_, _) => _exportDocuments.Clear();
        Backstage.ExportSettings.Refresh(); Backstage.PrintSettings.Refresh();
    }

    private IReadOnlyList<NotePage> ExportPages(ExportScope scope)
    {
        var notebook = ViewModel.ActiveWorkspace;
        IEnumerable<NotePage> Visit(IEnumerable<SectionNode> roots) => roots.SelectMany(s => s.Pages.Concat(Visit(s.Children)));
        return scope switch
        {
            ExportScope.Page => notebook.FindPage(ViewModel.FocusedDocument?.PageId, out _) is { } page ? [page] : [],
            ExportScope.Section => notebook.SelectedSection() is { } section ? Visit([section]).ToArray() : [],
            _ => Visit(notebook.Sections).ToArray(),
        };
    }

    private PageDocument ExportDocument(NotePage page)
    {
        foreach (var live in new[] { ViewModel.PrimaryDocument, ViewModel.SecondaryDocument })
            if (live?.NotebookId == ViewModel.ActiveWorkspace.Id && live.PageId == page.Id) return live;
        if (!_exportDocuments.TryGetValue(page.Id, out var document))
            _exportDocuments[page.Id] = document = ViewModel.Store.LoadPage(ViewModel.ActiveWorkspace.Id, page.Id);
        return document;
    }

    private (string Target, string Summary) DescribeExport(ExportOptions options)
    {
        var pages = ExportPages(options.Scope);
        if (pages.Count == 0) throw new InvalidOperationException(options.Scope == ExportScope.Section ? "请先选择一个分区。" : "当前范围没有页面。");
        var title = options.Scope switch { ExportScope.Page => pages[0].Title, ExportScope.Section => ViewModel.ActiveSectionTitle, _ => ViewModel.ActiveNotebookTitle };
        if (options.Format == ExportFormat.Package) return (title, $"{pages.Count} 个笔记页面，包含可编辑内容和原始附件");
        var count = pages.Sum(page => ExportLayout.Plan(page, ExportDocument(page), options).Count);
        var selected = ExportLayout.SelectPages(options.PageRange, count);
        return (title, $"{pages.Count} 个笔记页面 → {count} 张纸，本次输出 {selected.Count} 页");
    }

    private IReadOnlyList<PdfVectorPage> CaptureExport(ExportOptions options)
    {
        var result = new List<PdfVectorPage>();
        foreach (var page in ExportPages(options.Scope))
        {
            var document = ExportDocument(page);
            var canvas = new NoteCanvas { Document = document, Appearance = ViewModel.DisplayAppearance(page.Appearance), PageTitle = page.Title,
                ResolveAssetPath = name => ViewModel.ResolveAsset(document, name) };
            result.AddRange(PaginatedExportService.Capture(canvas, page, options));
        }
        if (result.Count == 0) throw new InvalidOperationException("当前范围没有页面。");
        return ExportLayout.SelectPages(options.PageRange, result.Count).Select(i => result[i]).ToArray();
    }

    private void RunExport(ExportOptions options, bool printing, bool preview)
    {
        try
        {
            ViewModel.SaveAll();
            var description = DescribeExport(options);
            if (printing || preview)
            {
                var pages = CaptureExport(options);
                var document = PaginatedExportService.FixedDocument(pages);
                if (preview)
                {
                    var viewer = new DocumentViewer { Document = document };
                    new Window { Owner = this, Title = $"分页预览 · {description.Target} · {pages.Count} 页", Width = 940, Height = 780,
                        Content = viewer, WindowStartupLocation = WindowStartupLocation.CenterOwner }.Show();
                }
                else
                {
                    var dialog = new PrintDialog();
                    dialog.PrintTicket.PageMediaSize = new PageMediaSize(pages[0].Bounds.Width, pages[0].Bounds.Height);
                    dialog.PrintTicket.PageOrientation = PageOrientation.Portrait; // Sheet geometry already owns orientation.
                    if (dialog.ShowDialog() == true) dialog.PrintDocument(document.DocumentPaginator, description.Target);
                }
                return;
            }
            var (filter, extension) = options.Format switch
            {
                ExportFormat.Package => ("墨笺可编辑包 (*.mjn)|*.mjn", ".mjn"),
                ExportFormat.PngZip => ("分页 PNG 图片 (*.zip)|*.zip", ".zip"),
                ExportFormat.Xps => ("XPS 文档 (*.xps)|*.xps", ".xps"),
                _ => ("PDF 文件 (*.pdf)|*.pdf", ".pdf"),
            };
            var picker = new SaveFileDialog { Title = "导出", Filter = filter, FileName = description.Target + extension, DefaultExt = extension, AddExtension = true };
            if (picker.ShowDialog(this) != true) return;
            // All formats replace the selected destination, including ZIP-based packages.
            var temporary = Path.Combine(Path.GetDirectoryName(picker.FileName)!, $".{Guid.NewGuid():N}{extension}");
            try
            {
                if (options.Format == ExportFormat.Package)
                {
                    var notebook = ViewModel.ActiveWorkspace;
                    if (options.Scope == ExportScope.Page)
                        NotebookPackageService.ExportPage(temporary, notebook, ExportPages(options.Scope)[0], ViewModel.Store);
                    else if (options.Scope == ExportScope.Section)
                        NotebookPackageService.ExportSection(temporary, notebook, notebook.SelectedSection()!, ViewModel.Store);
                    else NotebookPackageService.Export(temporary, notebook, ViewModel.Store, ViewModel.FocusedDocument?.PageId);
                }
                else
                {
                    var pages = CaptureExport(options);
                    switch (options.Format)
                    {
                        case ExportFormat.Pdf: VectorPdfExportService.Write(temporary, pages); break;
                        case ExportFormat.PngZip: PaginatedExportService.WritePngZip(temporary, pages); break;
                        case ExportFormat.Xps: PaginatedExportService.WriteXps(temporary, pages); break;
                    }
                }
                File.Move(temporary, picker.FileName, true);
            }
            finally { if (File.Exists(temporary)) File.Delete(temporary); }
            MessageBox.Show(this, "导出完成。\n" + description.Summary, "墨笺", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception error) { MessageBox.Show(this, error.Message, printing ? "打印失败" : "导出失败", MessageBoxButton.OK, MessageBoxImage.Error); }
    }
}
