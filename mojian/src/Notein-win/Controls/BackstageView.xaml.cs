using System.Windows;
using System.Windows.Controls;
using Notein_win.Models;

namespace Notein_win.Controls;

public partial class BackstageView : UserControl
{
    public event EventHandler? NewNotebookRequested;
    public event EventHandler? OpenWorkspaceRequested;
    public event EventHandler? SwitchWorkspaceRequested;
    public event EventHandler? ExportPdfRequested;
    public event EventHandler? ExportPackageRequested;
    public event Action<ShortcutActionDescriptor>? ConfigureShortcutRequested;
    public event Action<ShortcutActionDescriptor>? ClearShortcutRequested;

    public BackstageView() { InitializeComponent(); PrintSettings.Printing = true; }

    private void OnNewNotebookClick(object sender, RoutedEventArgs e) => NewNotebookRequested?.Invoke(this, EventArgs.Empty);
    private void OnOpenWorkspaceClick(object sender, RoutedEventArgs e) => OpenWorkspaceRequested?.Invoke(this, EventArgs.Empty);
    private void OnSwitchWorkspaceClick(object sender, RoutedEventArgs e) => SwitchWorkspaceRequested?.Invoke(this, EventArgs.Empty);
    private void OnExportPdfClick(object sender, RoutedEventArgs e) => ExportPdfRequested?.Invoke(this, EventArgs.Empty);
    private void OnExportPackageClick(object sender, RoutedEventArgs e) => ExportPackageRequested?.Invoke(this, EventArgs.Empty);
    private void OnConfigureShortcutClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ShortcutActionDescriptor action }) ConfigureShortcutRequested?.Invoke(action);
    }

    private void OnClearShortcutClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ShortcutActionDescriptor action }) ClearShortcutRequested?.Invoke(action);
    }
}
