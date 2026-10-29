using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Models;
using Notein_win.Services;
using Notein_win.ViewModels;

namespace Notein_win.Tests;

public class NotebookNavigationUiTests
{
    [Fact]
    public void InactiveNotebookTemplatesShowSectionsAndRouteActionsToTheirOwner()
    {
        WpfTestExecutor.Run(() =>
        {
            foreach (var name in new[] { "DarkColors", "IconResources", "ControlStyles" })
                Application.Current.Resources.MergedDictionaries.Add((ResourceDictionary)Application.LoadComponent(
                    new Uri($"/Mojian;component/Themes/{name}.xaml", UriKind.Relative)));
            var root = Path.Combine(Path.GetTempPath(), ".agents", "mojian-notebook-navigation-ui", Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            MainWindow? window = null;
            ContextMenu? menu = null;
            try
            {
                var model = new MainViewModel(Path.Combine(root, "workspace"), new PreferencesStore(Path.Combine(root, "preferences.json")));
                var inactive = model.ActiveWorkspace;
                var section = inactive.Sections[0];
                model.AddChildSection(section.Id, "Nested inactive section");
                var child = Assert.Single(section.Children);
                model.SelectSectionCommand.Execute(section.Id);
                model.AddNotebook("Focused notebook");
                var activeId = model.ActiveWorkspace.Id;
                var document = model.FocusedDocument;
                window = new MainWindow(model) { Width = 1280, Height = 820 };
                window.Show();
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
                var pane = (FrameworkElement)window.FindName("SectionPane");
                var sectionBorder = SectionBorder(pane, section.Id);
                Assert.True(sectionBorder.IsVisible);
                Assert.True(SectionBorder(pane, child.Id).IsVisible);
                var chevron = Assert.Single(Descendants(sectionBorder).OfType<Button>(), button => button.Command == model.ToggleSectionExpandCommand);
                Assert.Equal(section.Id, chevron.CommandParameter);
                chevron.Command.Execute(chevron.CommandParameter);
                window.UpdateLayout();
                Assert.DoesNotContain(Descendants(pane).OfType<Border>(), border => border.Child is Grid && border.DataContext is SectionRow row && row.Section.Id == child.Id);
                sectionBorder = SectionBorder(pane, section.Id);
                chevron = Assert.Single(Descendants(sectionBorder).OfType<Button>(), button => button.Command == model.ToggleSectionExpandCommand);
                chevron.Command.Execute(chevron.CommandParameter);
                window.UpdateLayout();
                Assert.True(SectionBorder(pane, child.Id).IsVisible);
                Assert.Equal(activeId, model.ActiveWorkspace.Id);
                Assert.Same(document, model.FocusedDocument);

                sectionBorder = SectionBorder(pane, section.Id);
                var title = Assert.Single(Descendants(sectionBorder).OfType<TextBlock>());
                var rightClick = new MouseButtonEventArgs(Mouse.PrimaryDevice, Environment.TickCount, MouseButton.Right)
                {
                    RoutedEvent = Mouse.PreviewMouseUpEvent,
                };
                title.RaiseEvent(rightClick);
                Assert.True(rightClick.Handled);
                window.Dispatcher.Invoke(() => { }, DispatcherPriority.Background);
                menu = Assert.Single(PresentationSource.CurrentSources.OfType<PresentationSource>()
                    .Where(source => source.Dispatcher.CheckAccess())
                    .SelectMany(source => source.RootVisual == null ? [] : Descendants(source.RootVisual))
                    .OfType<ContextMenu>(), candidate => candidate.PlacementTarget == sectionBorder);
                var moveOrCopy = Assert.Single(menu.Items.OfType<MenuItem>(), item => Equals(item.Header, "移动或复制…"));
                var moveMenu = Assert.Single(moveOrCopy.Items.OfType<MenuItem>(), item => Equals(item.Header, "移动到"));
                Assert.Equal(inactive.Title, Assert.Single(moveMenu.Items.OfType<MenuItem>()).Header);
                Assert.Equal(activeId, model.ActiveWorkspace.Id);
                menu.IsOpen = false;

                var output = Path.Combine(Path.GetTempPath(), ".agents", "mojian-notebook-navigation-20261029", "qa");
                Directory.CreateDirectory(output);
                var bitmap = new RenderTargetBitmap(1280, 820, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render((Visual)window.Content);
                var encoder = new PngBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using (var stream = File.Create(Path.Combine(output, "inactive-notebook-expanded.png"))) encoder.Save(stream);

                var grid = Assert.IsType<Grid>(SectionBorder(pane, child.Id).Child);
                var binding = Assert.Single(grid.InputBindings.OfType<MouseBinding>());
                Assert.Equal(model.SelectSectionCommand, binding.Command);
                binding.Command.Execute(binding.CommandParameter);
                Assert.Equal(inactive.Id, model.ActiveWorkspace.Id);
                Assert.Equal(child.Id, model.ActiveWorkspace.FocusedSectionId);
            }
            finally
            {
                if (menu != null) menu.IsOpen = false;
                window?.Close();
                Directory.Delete(root, true);
            }
        });
    }

    private static Border SectionBorder(DependencyObject pane, string sectionId) =>
        Descendants(pane).OfType<Border>().First(border => border.Child is Grid && border.DataContext is SectionRow row && row.Section.Id == sectionId);

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        yield return root;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            foreach (var child in Descendants(VisualTreeHelper.GetChild(root, index))) yield return child;
    }
}
