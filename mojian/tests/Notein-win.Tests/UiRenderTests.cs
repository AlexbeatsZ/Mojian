using System;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using Notein_win.Controls;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;
using Xunit;

namespace Notein_win.Tests;

public class UiRenderTests
{
    [Fact]
    public void GenerateUiScreenshots()
    {
        string errorFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", ".agents", "ui_compare", "render_error.txt");
        if (File.Exists(errorFile)) File.Delete(errorFile);
        var thread = new Thread(() =>
        {
            try
            {
                var application = Application.Current ?? new Application();
                application.Resources.MergedDictionaries.Clear();
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/DarkColors.xaml", UriKind.Relative)));
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/IconResources.xaml", UriKind.Relative)));
                application.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/ControlStyles.xaml", UriKind.Relative)));

                var testRoot=Path.Combine(Path.GetTempPath(),".agents","mojian-ui-render",Guid.NewGuid().ToString("N"));
                var testModel=new MainViewModel(testRoot,new PreferencesStore(Path.Combine(testRoot,"preferences.json")));
                var window = new MainWindow(testModel)
                {
                    Width = 1440,
                    Height = 900
                };
                window.Show();

                var vm = (MainViewModel)window.DataContext;

                string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", ".agents", "ui_compare");
                Directory.CreateDirectory(outDir);

                void CaptureVisual(FrameworkElement element, int width, int height, string filename)
                {
                    element.Measure(new Size(width, height));
                    element.Arrange(new Rect(0, 0, width, height));
                    element.UpdateLayout();
                    System.Windows.Threading.Dispatcher.CurrentDispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Render);

                    var rtb = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
                    if (element is Window win && win.Content is Visual contentVisual)
                    {
                        rtb.Render(contentVisual);
                    }
                    else
                    {
                        rtb.Render(element);
                    }

                    var encoder = new PngBitmapEncoder();
                    encoder.Frames.Add(BitmapFrame.Create(rtb));
                    using var stream = File.Create(Path.Combine(outDir, filename));
                    encoder.Save(stream);
                }

                // 1. Draw tab
                vm.ActiveRibbonTab = RibbonTab.Draw;
                vm.IsBackstageOpen = false;
                CaptureVisual(window, 1440, 900, "win_1_draw.png");

                // 2. Start (Home) tab
                vm.ActiveRibbonTab = RibbonTab.Home;
                CaptureVisual(window, 1440, 900, "win_2_start.png");

                // 3. Insert tab
                vm.ActiveRibbonTab = RibbonTab.Insert;
                CaptureVisual(window, 1440, 900, "win_3_insert.png");

                // 4. View tab
                vm.ActiveRibbonTab = RibbonTab.View;
                CaptureVisual(window, 1440, 900, "win_4_view.png");
                var normalFullPageButton = Assert.IsType<Button>(window.FindName("EnterFullPageButton"));
                AssertRenderedIconIsCentered(normalFullPageButton);
                var leftCanvas = Assert.IsType<NoteCanvas>(window.FindName("LeftCanvas"));
                vm.SingleFingerPanEnabled = true;
                Assert.Same(System.Windows.Input.Cursors.Arrow, leftCanvas.Cursor);
                vm.SingleFingerPanEnabled = false;

                // 5. Add Page dialog
                var addPageDlg = new PageCreationDialog(vm.LastPageAppearance)
                {
                    Width = 540,
                    Height = 420
                };
                addPageDlg.Show();
                Assert.False(addPageDlg.AllowsTransparency);
                Assert.True(addPageDlg.UseLayoutRounding);
                Assert.True(addPageDlg.SnapsToDevicePixels);
                Assert.Equal(TextFormattingMode.Display, TextOptions.GetTextFormattingMode(addPageDlg));
                Assert.Equal(TextRenderingMode.ClearType, TextOptions.GetTextRenderingMode(addPageDlg));
                CaptureVisual(addPageDlg, 540, 420, "win_5_addpage.png");
                addPageDlg.Close();

                // 6. Backstage Info
                vm.IsBackstageOpen = true;
                vm.SelectBackstageTabCommand.Execute("Info");
                CaptureVisual(window, 1440, 900, "win_6_backstage_info.png");

                // 7. Backstage Options
                vm.SelectBackstageTabCommand.Execute("Options");
                CaptureVisual(window, 1440, 900, "win_7_backstage_options.png");
                var backstage = Assert.IsType<BackstageView>(window.FindName("Backstage"));
                var backstageNavigationColumn = Assert.IsType<ColumnDefinition>(backstage.FindName("BackstageNavigationColumn"));
                Assert.Equal(160, backstageNavigationColumn.ActualWidth);

                // 8. Backstage Shortcuts
                vm.SelectBackstageTabCommand.Execute("Shortcuts");
                CaptureVisual(window, 1440, 900, "win_8_backstage_shortcuts.png");

                // 9. Full-page chrome: transient panes start closed and the rail leaves no dead column.
                vm.IsBackstageOpen = false;
                vm.IsFullPageMode = true;
                CaptureVisual(window, 1440, 900, "win_9_fullpage.png");
                var workspaceLayout = Assert.IsType<Grid>(window.FindName("WorkspaceLayout"));
                var sectionPane = Assert.IsAssignableFrom<FrameworkElement>(window.FindName("SectionPane"));
                var pagePane = Assert.IsAssignableFrom<FrameworkElement>(window.FindName("PagePane"));
                var canvasActions = Assert.IsAssignableFrom<FrameworkElement>(window.FindName("CanvasActions"));
                var enterFullPage = Assert.IsType<Button>(window.FindName("EnterFullPageButton"));
                var exitFullPage = Assert.IsType<Button>(window.FindName("ExitFullPageButton"));
                var addSection = Assert.IsType<Button>(window.FindName("AddSectionButton"));
                var addPage = Assert.IsType<Button>(window.FindName("AddPageButton"));

                Assert.Equal(0, workspaceLayout.ColumnDefinitions[0].ActualWidth);
                Assert.False(sectionPane.IsVisible);
                Assert.False(pagePane.IsVisible);
                Assert.True(WindowChrome.GetIsHitTestVisibleInChrome(canvasActions));
                Assert.True(WindowChrome.GetIsHitTestVisibleInChrome(addSection));
                Assert.True(WindowChrome.GetIsHitTestVisibleInChrome(addPage));
                Assert.Equal(new Thickness(6), enterFullPage.Padding);
                Assert.Equal(new Thickness(6), exitFullPage.Padding);
                Assert.Equal(34, exitFullPage.ActualWidth);
                AssertRenderedIconIsCentered(exitFullPage);

                vm.ToggleSectionPaneCommand.Execute(null);
                vm.TogglePagePaneCommand.Execute(null);
                CaptureVisual(window, 1440, 900, "win_10_fullpage_panes.png");
                Assert.Equal(196, sectionPane.ActualWidth);
                Assert.Equal(196, pagePane.ActualWidth);
                var eraserSlider = Assert.IsType<Slider>(window.FindName("EraserSlider"));
                Assert.Equal(ToolPreset.MinimumEraserRadiusMm, eraserSlider.Minimum);
                Assert.Equal(ToolPreset.MaximumEraserRadiusMm, eraserSlider.Maximum);
                var panoramaSlider = Assert.IsType<Slider>(window.FindName("PanoramaSensitivitySlider"));
                Assert.Equal(MainViewModel.MinimumPanoramaSensitivity, panoramaSlider.Minimum);
                Assert.Equal(MainViewModel.MaximumPanoramaSensitivity, panoramaSlider.Maximum);

                // Exercise the real notebook template's routed right-click, including an inactive row.
                vm.IsFullPageMode = false;
                var inactiveId = vm.ActiveWorkspace.Id;
                vm.AddNotebook("Context menu target");
                var activeId = vm.ActiveWorkspace.Id;
                window.UpdateLayout();
                var notebookBorder = VisualDescendants(window).OfType<Border>().First(element =>
                    element.DataContext is NotebookPaneRow row && row.Id == inactiveId && element.ContextMenu == null && element.Padding == new Thickness(5, 0, 7, 0));
                var title = VisualDescendants(notebookBorder).OfType<TextBlock>().Last();
                var rightClick = new System.Windows.Input.MouseButtonEventArgs(System.Windows.Input.Mouse.PrimaryDevice, Environment.TickCount, System.Windows.Input.MouseButton.Right)
                {
                    RoutedEvent = System.Windows.Input.Mouse.PreviewMouseUpEvent,
                };
                title.RaiseEvent(rightClick);
                Assert.True(rightClick.Handled);
                var notebookMenu = Assert.IsType<ContextMenu>(notebookBorder.ContextMenu);
                Assert.True(notebookMenu.IsOpen);
                Assert.Equal(new[] { "重命名", "删除", "新建分区" }, notebookMenu.Items.OfType<MenuItem>().Select(item => item.Header?.ToString()));
                Assert.Equal(activeId, vm.ActiveWorkspace.Id);
                CaptureVisual(notebookMenu, 244, 114, "win_11_notebook_menu.png");
                notebookMenu.IsOpen = false;

                // Reuse this WPF window: a second Application/window lifetime can shut down
                // the shared test dispatcher and break later real-window assertions.
                vm.SelectToolFromToolbar("shape-default", false);
                vm.IsToolFlyoutOpen = true;
                var shapePopup = Assert.IsType<System.Windows.Controls.Primitives.Popup>(window.FindName("ToolFlyoutPopup"));
                shapePopup.PlacementTarget = window;
                shapePopup.SetCurrentValue(System.Windows.Controls.Primitives.Popup.IsOpenProperty, true);
                window.UpdateLayout();
                System.Windows.Threading.Dispatcher.CurrentDispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Render);
                var shapeChooser = Assert.IsType<ComboBox>(window.FindName("ShapeKindChooser"));
                Assert.True(shapeChooser.IsVisible);
                Assert.Equal(7, shapeChooser.Items.Count);
                shapeChooser.SelectedItem = ShapeKind.Rectangle;
                var shapeDelay = Assert.IsType<Slider>(window.FindName("StraightenDelaySlider"));
                Assert.True(shapeDelay.IsVisible);
                Assert.Equal(1000, shapeDelay.Value);
                shapeDelay.Value = 1250;
                vm.SaveToolSettings();
                var restoredShapes = new MainViewModel(testRoot, new PreferencesStore(Path.Combine(testRoot, "preferences.json")));
                Assert.Equal(ShapeKind.Rectangle, restoredShapes.ShapeSettings.ShapeKind);
                Assert.Equal(1250, restoredShapes.ShapeSettings.StraightenDelayMillis);
                var shapePanel = Assert.IsAssignableFrom<FrameworkElement>(shapePopup.Child);
                shapePanel.Measure(new Size(316, double.PositiveInfinity));
                CaptureVisual(shapePanel, 316, (int)Math.Ceiling(shapePanel.DesiredSize.Height), "win_12_shapes.png");
                shapePopup.IsOpen = false;
                vm.SelectToolFromToolbar("eraser-stroke", false);
                vm.IsToolFlyoutOpen = true;
                shapePopup.SetCurrentValue(System.Windows.Controls.Primitives.Popup.IsOpenProperty, true);
                window.UpdateLayout();
                var travelSlider = Assert.IsType<Slider>(window.FindName("EraserTravelLimitSlider"));
                Assert.True(travelSlider.IsVisible);
                Assert.Equal(.1, travelSlider.Minimum); Assert.Equal(10, travelSlider.Maximum);
                Assert.Equal(1, travelSlider.Value); travelSlider.Value = 2.3;
                vm.SaveToolSettings();
                var restoredEraser = new MainViewModel(testRoot, new PreferencesStore(Path.Combine(testRoot, "preferences.json")));
                Assert.Equal(2.3f, restoredEraser.PressureEraserTool.PressureEraseTravelLimitMm);
                shapePanel.Measure(new Size(316, double.PositiveInfinity));
                CaptureVisual(shapePanel, 316, (int)Math.Ceiling(shapePanel.DesiredSize.Height), "win_13_eraser_travel.png");
                shapePopup.IsOpen = false;
                window.Close();
            }
            catch (Exception ex)
            {
                string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", ".agents", "ui_compare");
                Directory.CreateDirectory(outDir);
                File.WriteAllText(Path.Combine(outDir, "render_error.txt"), ex.ToString());
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();

        if (File.Exists(errorFile))
        {
            string err = File.ReadAllText(errorFile);
            File.Delete(errorFile);
            Assert.Fail("Render error: " + err);
        }
    }

    private static IEnumerable<DependencyObject> VisualDescendants(DependencyObject root)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            yield return child;
            foreach (var descendant in VisualDescendants(child)) yield return descendant;
        }
    }

    private static void AssertRenderedIconIsCentered(Button button)
    {
        var width = (int)Math.Round(button.ActualWidth);
        var height = (int)Math.Round(button.ActualHeight);
        var visual = new DrawingVisual();
        using (var drawing = visual.RenderOpen())
            drawing.DrawRectangle(new VisualBrush(button), null, new Rect(0, 0, width, height));
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var pixels = new byte[width * height * 4];
        bitmap.CopyPixels(pixels, width * 4, 0);
        var lit = new List<Point>();
        for (var y = 2; y < height - 2; y++)
        for (var x = 2; x < width - 2; x++)
        {
            var offset = (y * width + x) * 4;
            if (pixels[offset] > 100 && pixels[offset + 1] > 100 && pixels[offset + 2] > 100)
                lit.Add(new Point(x, y));
        }

        Assert.NotEmpty(lit);
        var iconCenter = new Point((lit.Min(p => p.X) + lit.Max(p => p.X)) / 2, (lit.Min(p => p.Y) + lit.Max(p => p.Y)) / 2);
        Assert.InRange(Math.Abs(iconCenter.X - (width - 1) / 2d), 0, 0.5);
        Assert.InRange(Math.Abs(iconCenter.Y - (height - 1) / 2d), 0, 0.5);
    }

}
