using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using Notein_win.Models;
using Notein_win.ViewModels;

namespace Notein_win;

public partial class MainWindow : Window
{
    private MainViewModel? ViewModel => DataContext as MainViewModel;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (ViewModel != null)
        {
            ViewModel.RequestUndo += () => CanvasControl.Undo();
            ViewModel.RequestClearPage += () => CanvasControl.ClearPage();
            ViewModel.RequestPlaceTag += kind => CanvasControl.PendingTagKind = kind;
        }
    }

    private void OnAddSectionMenuClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && ViewModel != null)
        {
            var menu = new ContextMenu();

            var newNotebookItem = new MenuItem { Header = "新建笔记本" };
            newNotebookItem.Click += (s, ev) => ViewModel.AddNotebookCommand.Execute(null);

            var newSectionItem = new MenuItem { Header = "新建分区" };
            newSectionItem.Click += (s, ev) => ViewModel.AddSectionCommand.Execute(null);

            menu.Items.Add(newNotebookItem);
            menu.Items.Add(newSectionItem);
            menu.PlacementTarget = btn;
            menu.IsOpen = true;
        }
    }

    private void OnBackgroundFormatClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button btn && ViewModel != null)
        {
            var menu = new ContextMenu();

            void AddFormatItem(string label, CanvasBackgroundFormat fmt)
            {
                var item = new MenuItem { Header = label, IsChecked = ViewModel.BackgroundFormat == fmt };
                item.Click += (s, ev) => ViewModel.SetBackgroundFormatCommand.Execute(fmt);
                menu.Items.Add(item);
            }

            AddFormatItem("标准网格", CanvasBackgroundFormat.GridStandard);
            AddFormatItem("密集网格", CanvasBackgroundFormat.GridDense);
            AddFormatItem("宽网格", CanvasBackgroundFormat.GridWide);
            menu.Items.Add(new Separator());
            AddFormatItem("标准横线", CanvasBackgroundFormat.RuledStandard);
            AddFormatItem("窄横线", CanvasBackgroundFormat.RuledNarrow);
            AddFormatItem("宽横线", CanvasBackgroundFormat.RuledWide);
            menu.Items.Add(new Separator());
            AddFormatItem("标准点阵", CanvasBackgroundFormat.DotsStandard);
            AddFormatItem("密集点阵", CanvasBackgroundFormat.DotsDense);
            AddFormatItem("宽点阵", CanvasBackgroundFormat.DotsWide);
            menu.Items.Add(new Separator());
            AddFormatItem("无横线 / 空白", CanvasBackgroundFormat.Blank);

            menu.PlacementTarget = btn;
            menu.IsOpen = true;
        }
    }

    private void OnInsertTableClick(object sender, RoutedEventArgs e)
    {
        var nextPt = CanvasControl.Viewport.ViewToDocument(50, 80);
        var table = new TableObject(
            Guid.NewGuid().ToString(),
            (float)nextPt.X,
            (float)nextPt.Y,
            300,
            120,
            rows: 3,
            columns: 3,
            cells: new[] { "序号", "项目", "状态", "1", "前端移植", "进行中", "2", "笔迹引擎", "已就绪" }
        );

        var doc = new PageInsertDocument(ViewModel?.ActiveWorkspace.Id ?? "default", ViewModel?.ActiveWorkspace.SelectedPageId ?? "page");
        doc.Objects.Add(table);
        CanvasControl.BindDocument(doc);
    }

    private void OnInsertAttachmentClick(object sender, RoutedEventArgs e)
    {
        var ofd = new OpenFileDialog { Title = "选择要嵌入的附件文件" };
        if (ofd.ShowDialog() == true)
        {
            var fileInfo = new System.IO.FileInfo(ofd.FileName);
            var nextPt = CanvasControl.Viewport.ViewToDocument(50, 100);
            var attachment = new AttachmentObject(
                Guid.NewGuid().ToString(),
                (float)nextPt.X,
                (float)nextPt.Y,
                260,
                56,
                fileInfo.Name,
                fileInfo.Name,
                "application/octet-stream",
                fileInfo.Length
            );

            var doc = new PageInsertDocument(ViewModel?.ActiveWorkspace.Id ?? "default", ViewModel?.ActiveWorkspace.SelectedPageId ?? "page");
            doc.Objects.Add(attachment);
            CanvasControl.BindDocument(doc);
        }
    }

    private void OnInsertImageClick(object sender, RoutedEventArgs e)
    {
        var ofd = new OpenFileDialog
        {
            Title = "选择图片",
            Filter = "图片文件 (*.png;*.jpg;*.jpeg;*.webp)|*.png;*.jpg;*.jpeg;*.webp|所有文件 (*.*)|*.*"
        };
        if (ofd.ShowDialog() == true)
        {
            var fileInfo = new System.IO.FileInfo(ofd.FileName);
            var nextPt = CanvasControl.Viewport.ViewToDocument(50, 120);
            var imageObj = new ImageObject(
                Guid.NewGuid().ToString(),
                (float)nextPt.X,
                (float)nextPt.Y,
                280,
                200,
                fileInfo.Name,
                fileInfo.Name,
                "image/png",
                ImageSourceKind.Image,
                viewInverted: false
            );

            var doc = new PageInsertDocument(ViewModel?.ActiveWorkspace.Id ?? "default", ViewModel?.ActiveWorkspace.SelectedPageId ?? "page");
            doc.Objects.Add(imageObj);
            CanvasControl.BindDocument(doc);
        }
    }

    private void OnInsertSpreadsheetClick(object sender, RoutedEventArgs e)
    {
        var nextPt = CanvasControl.Viewport.ViewToDocument(50, 140);
        var sheet = new SpreadsheetObject(
            Guid.NewGuid().ToString(),
            (float)nextPt.X,
            (float)nextPt.Y,
            320,
            160,
            "预算概览.xlsx",
            "预算概览.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            new List<List<string>>
            {
                new() { "科目", "预算", "实际" },
                new() { "设备", "¥5,000", "¥4,200" },
                new() { "研发", "¥12,000", "¥10,500" }
            }
        );

        var doc = new PageInsertDocument(ViewModel?.ActiveWorkspace.Id ?? "default", ViewModel?.ActiveWorkspace.SelectedPageId ?? "page");
        doc.Objects.Add(sheet);
        CanvasControl.BindDocument(doc);
    }

    private void OnInsertLinkClick(object sender, RoutedEventArgs e)
    {
        var nextPt = CanvasControl.Viewport.ViewToDocument(50, 160);
        var link = new NoteLinkObject(
            Guid.NewGuid().ToString(),
            (float)nextPt.X,
            (float)nextPt.Y,
            200,
            30,
            ViewModel?.ActiveWorkspace.Id ?? "default",
            ViewModel?.ActiveWorkspace.SelectedSectionId ?? "section",
            ViewModel?.ActiveWorkspace.SelectedPageId ?? "page",
            "跳转到: 界面与交互"
        );

        var doc = new PageInsertDocument(ViewModel?.ActiveWorkspace.Id ?? "default", ViewModel?.ActiveWorkspace.SelectedPageId ?? "page");
        doc.Objects.Add(link);
        CanvasControl.BindDocument(doc);
    }
}