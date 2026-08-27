using System;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
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
        var thread = new Thread(() =>
        {
            try
            {
                if (Application.Current == null)
                {
                    _ = new Application();
                }
                Application.Current.Resources.MergedDictionaries.Clear();
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/DarkColors.xaml", UriKind.Relative)));
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/IconResources.xaml", UriKind.Relative)));
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(new Uri("/Mojian;component/Themes/ControlStyles.xaml", UriKind.Relative)));

                var window = new MainWindow
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

                // 5. Add Page dialog
                var addPageDlg = new PageCreationDialog(vm.LastPageAppearance)
                {
                    Width = 540,
                    Height = 420
                };
                addPageDlg.Show();
                CaptureVisual(addPageDlg, 540, 420, "win_5_addpage.png");
                addPageDlg.Close();

                // 6. Backstage Info
                vm.IsBackstageOpen = true;
                vm.SelectBackstageTabCommand.Execute("Info");
                CaptureVisual(window, 1440, 900, "win_6_backstage_info.png");

                // 7. Backstage Options
                vm.SelectBackstageTabCommand.Execute("Options");
                CaptureVisual(window, 1440, 900, "win_7_backstage_options.png");

                // 8. Backstage Shortcuts
                vm.SelectBackstageTabCommand.Execute("Shortcuts");
                CaptureVisual(window, 1440, 900, "win_8_backstage_shortcuts.png");
            }
            catch (Exception ex)
            {
                string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", ".agents", "ui_compare");
                Directory.CreateDirectory(outDir);
                File.WriteAllText(Path.Combine(outDir, "render_error.txt"), ex.ToString());
                throw;
            }
        });

        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();

        string errorFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp", ".agents", "ui_compare", "render_error.txt");
        if (File.Exists(errorFile))
        {
            string err = File.ReadAllText(errorFile);
            File.Delete(errorFile);
            Assert.Fail("Render error: " + err);
        }
    }
}
