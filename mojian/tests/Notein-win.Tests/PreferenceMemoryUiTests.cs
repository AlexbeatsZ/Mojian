using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class PreferenceMemoryUiTests
{
    [Fact]
    public void BrowsingA4DoesNotPublishChangesAndViewportRestoresLocallyAfterClose()
    {
        WpfTestExecutor.Run(() =>
        {
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(
                    new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-preference-memory", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            var preferences = new PreferencesStore(Path.Combine(root, "preferences.json"));
            MainWindow? window = null;
            try
            {
                var model = new MainViewModel(root, preferences);
                model.AddPage("A4 browsing", new PageAppearance { PaperKind = PaperKind.A4, SheetCount = 1 });
                model.SaveAll();
                window = new MainWindow(model) { Width = 1280, Height = 820 };
                window.Show();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                var canvas = (NoteCanvas)window.FindName("LeftCanvas");
                var document = model.PrimaryDocument!;
                var path = model.Store.GetPageDocumentPath(document.NotebookId, document.PageId);
                var pageBytes = File.ReadAllBytes(path);
                var libraryBytes = File.ReadAllBytes(model.Store.LibraryPath);
                var published = 0;
                model.LocalSaveCompleted += () => published++;
                canvas.Viewport.Pan(-800, -24000);
                canvas.SetZoom(0.5f);
                canvas.SetZoom(4f);
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                Assert.Equal(1, canvas.Appearance.SheetCount);
                Assert.Equal(1, model.ActiveWorkspace.SelectedPage()!.Appearance.SheetCount);
                Assert.Equal(pageBytes, File.ReadAllBytes(path));
                Assert.Equal(libraryBytes, File.ReadAllBytes(model.Store.LibraryPath));
                Assert.Equal(0, published);
                var expected = new PageViewport();
                canvas.Viewport.CopyTo(expected);
                var status = (StackPanel)window.FindName("CanvasStatus");
                var parent = (FrameworkElement)status.Parent;
                var position = status.TranslatePoint(new Point(), parent);
                Assert.InRange(parent.ActualWidth - position.X - status.ActualWidth, 11, 13);
                Assert.InRange(parent.ActualHeight - position.Y - status.ActualHeight, 9, 11);
                Capture(window, "local-viewport-status.png");
                window.Close();
                window = null;
                Assert.Equal(pageBytes, File.ReadAllBytes(path));
                Assert.Equal(libraryBytes, File.ReadAllBytes(model.Store.LibraryPath));
                Assert.Equal(0, published);
                var restored = new MainViewModel(root, preferences);
                Assert.Equal(expected.Scale, restored.PrimaryDocument!.Viewport.Scale);
                Assert.Equal(expected.TranslationX, restored.PrimaryDocument.Viewport.TranslationX);
                Assert.Equal(expected.TranslationY, restored.PrimaryDocument.Viewport.TranslationY);
                window = new MainWindow(restored) { Width = 1280, Height = 820 };
                window.Show();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                canvas = (NoteCanvas)window.FindName("LeftCanvas");
                canvas.AddObject(new PageObject { Kind = PageObjectKind.Table, X = 100, Y = 3000, Width = 100, Height = 100 });
                restored.SaveAll();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                var saved = new WorkspaceStore(root).LoadPage(document.NotebookId, document.PageId);
                Assert.Single(saved.Objects);
                Assert.True(new WorkspaceStore(root).LoadLibrary().ActiveNotebook.SelectedPage()!.Appearance.SheetCount > 1);
                Assert.Equal(1f, saved.LegacyViewport.Scale);
                Assert.Equal(0f, saved.LegacyViewport.TranslationY);
                Assert.Equal(expected.Scale, restored.PrimaryDocument.Viewport.Scale);
            }
            finally { window?.Close(); Directory.Delete(root, true); }
        });
    }

    [Fact]
    public void BackgroundMenuNavigatesToDefaultSettingAndPrintPanelRestoresAfterWindowRestart()
    {
        WpfTestExecutor.Run(() =>
        {
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(
                    new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-preference-memory", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            var preferences = new PreferencesStore(Path.Combine(root, "preferences.json"));
            MainWindow? window = null;
            try
            {
                var model = new MainViewModel(root, preferences);
                window = new MainWindow(model) { Width = 1280, Height = 1050 };
                window.Show();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                var button = (Button)window.FindName("PageBackgroundButton");
                button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                var menu = Assert.IsType<ContextMenu>(button.ContextMenu);
                var link = Assert.Single(menu.Items.OfType<MenuItem>(), item => (string)item.Header == "设置默认页面背景…");
                link.RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent));
                menu.IsOpen = false;
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                Assert.True(model.IsBackstageOptions);
                var backstage = (BackstageView)window.FindName("Backstage");
                var picker = (Button)backstage.FindName("DefaultPageBackgroundPicker");
                Assert.True(picker.IsVisible);
                picker.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                var defaultMenu = picker.ContextMenu;
                Assert.Single(defaultMenu.Items.OfType<MenuItem>(), item => (string)item.Header == "窄双栏横线")
                    .RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent));
                defaultMenu.IsOpen = false;
                Assert.Equal(CanvasBackgroundFormat.RuledTwoColumnNarrow, model.DefaultPageBackground);
                foreach (var format in Enum.GetValues<CanvasBackgroundFormat>())
                {
                    var appearance = model.LastPageAppearance;
                    appearance.Background = format;
                    var creation = new PageCreationDialog(appearance);
                    try
                    {
                        var selected = ((ComboBox)creation.FindName("BackgroundPicker")).SelectedItem;
                        Assert.Equal(format, Assert.IsType<BackgroundFormatOption>(selected).Format);
                        Assert.Equal(model.IsDarkPageBackground ? PageTheme.Dark : PageTheme.Light, creation.PageAppearance.Theme);
                    }
                    finally { creation.Close(); }
                }
                Capture(window, "default-background.png");
                model.SelectBackstageTabCommand.Execute("Print");
                var panel = (ExportPanel)backstage.FindName("PrintSettings");
                ((ListBox)panel.FindName("ScopeList")).SelectedIndex = 2;
                ((ListBox)panel.FindName("PaperList")).SelectedIndex = 2;
                ((ListBox)panel.FindName("OrientationList")).SelectedIndex = 1;
                ((ListBox)panel.FindName("MarginList")).SelectedIndex = 3;
                ((CheckBox)panel.FindName("TrimCheck")).IsChecked = false;
                ((CheckBox)panel.FindName("HideFormatCheck")).IsChecked = true;
                ((TextBox)panel.FindName("RangeText")).Text = "1";
                var expected = PrintPreferences.From(panel.Options);
                window.Close();
                model = new MainViewModel(root, preferences);
                window = new MainWindow(model) { Width = 1280, Height = 1050 };
                window.Show();
                model.IsBackstageOpen = true;
                model.SelectBackstageTabCommand.Execute("Print");
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                backstage = (BackstageView)window.FindName("Backstage");
                panel = (ExportPanel)backstage.FindName("PrintSettings");
                Assert.Equal(expected, PrintPreferences.From(panel.Options));
                Assert.Equal(CanvasBackgroundFormat.RuledTwoColumnNarrow, model.LastPageAppearance.Background);
                Assert.True(panel.Options.Printing);
                Assert.False(((ExportPanel)backstage.FindName("ExportSettings")).Options.HideBackgroundFormat);
                Capture(window, "restored-print.png");
            }
            finally { window?.Close(); Directory.Delete(root, true); }
        });
    }

    private static void Capture(Window window, string filename)
    {
        window.UpdateLayout();
        var output = Path.Combine(Path.GetTempPath(), ".agents", "mojian-preference-memory", "qa");
        Directory.CreateDirectory(output);
        var bitmap = new RenderTargetBitmap((int)window.Width, (int)window.Height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render((Visual)window.Content);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = File.Create(Path.Combine(output, filename)); encoder.Save(stream);
    }
}
