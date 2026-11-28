using System.IO;
using System.Windows;
using System.Windows.Controls;
using Notein_win.Services;

namespace Notein_win;

/// <summary>
/// Receives the folder library into a library that is empty right after its v2 layout was retired, then opens it.
/// Progress and failures stay visible with their reason; nothing opens a half-received library.
/// </summary>
internal sealed class FirstReceiveWindow : Window
{
    private readonly string _root;
    private readonly Action _open;
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 16) };
    private readonly Button _retry = new() { Content = "重试", Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Left, Padding = new Thickness(16, 4, 16, 4) };
    private readonly string? _notice;

    public FirstReceiveWindow(string root, Action open)
    {
        _root = root; _open = open;
        var notice = Path.Combine(root, FolderNames.HiddenRoot, "legacy-v2-notice.txt");
        _notice = File.Exists(notice) ? File.ReadAllText(notice) : null;
        Title = "墨笺"; Width = 520; SizeToContent = SizeToContent.Height; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = new StackPanel { Margin = new Thickness(24), Children = { _status, _retry } };
        _retry.Click += async (_, _) => await Receive();
        Loaded += async (_, _) => await Receive();
    }

    private void Show(string text) => _status.Text = string.Join("\n\n",
        new[] { "笔记库已改为真实文件夹格式；旧格式文件已移到 .mojian/legacy-v2。", _notice, text }.Where(line => !string.IsNullOrEmpty(line)));

    private async Task Receive()
    {
        _retry.Visibility = Visibility.Collapsed;
        Show("正在从服务器接收笔记…");
        try
        {
            using var client = new NativeSyncClient(_root, NativeSyncConfiguration.Load(_root));
            client.SnapshotChanged += snapshot => Dispatcher.BeginInvoke(() => Show(snapshot.Message));
            await Task.Run(() => client.SyncAsync(action => { action(); return Task.FromResult(true); }));
            if (!Directory.EnumerateFiles(_root, FolderNames.Descriptor, SearchOption.AllDirectories).Any(path => !Path.GetRelativePath(_root, path).StartsWith('.')))
                throw new IOException(client.Status);
            _open();
            Close();
        }
        catch (Exception error)
        {
            Show("接收未完成：" + error.Message);
            _retry.Visibility = Visibility.Visible;
        }
    }
}
