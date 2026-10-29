using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.Win32;
using Notein_win.Services;

namespace Notein_win;

public partial class MainWindow
{
    private readonly CancellationTokenSource _nativeSyncLifetime = new();
    private bool _nativeSyncRunning;
    private bool _nativeSyncPending;
    private NativeSyncClient? _nativeSyncClient;
    private (WorkspaceStore Store, string Endpoint, string Token, string Addresses)? _nativeSyncClientKey;
    private CancellationTokenSource? _nativeSyncForeground;

    private void InitializeNativeSync()
    {
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        timer.Tick += async (_, _) => { timer.Interval = TimeSpan.FromSeconds(15); await RunNativeSync(); };
        ViewModel.LocalSaveCompleted += () =>
        {
            _nativeSyncPending = true;
            ViewModel.NativeSyncStatus = _nativeSyncClient is { Snapshot.Phase: NativeSyncPhase.Offline or NativeSyncPhase.Conflict or NativeSyncPhase.Error } client
                ? NativeSyncDisplayStatus(client.Snapshot) + " · 本地已保存，待上传"
                : "本地已保存，待上传（如已启用）";
            timer.Stop();
            timer.Interval = TimeSpan.FromSeconds(2);
            timer.Start();
        };
        Backstage.NativeSyncRequested += (_, _) => ShowNativeSyncSettings();
        Activated += async (_, _) => await RunNativeSync();
        Deactivated += (_, _) => _nativeSyncForeground?.Cancel();
        ViewModel.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(ViewModel.NativeSyncRecoveryRequired) && Content is UIElement content)
                content.IsEnabled = !ViewModel.NativeSyncRecoveryRequired;
        };
        timer.Start();
        Closed += (_, _) =>
        {
            timer.Stop();
            _nativeSyncLifetime.Cancel();
            if (!_nativeSyncRunning) ResetNativeSyncClient();
        };
        _ = RunNativeSync();
    }

    private bool NativeSyncIdle => IsActive && Mouse.LeftButton != MouseButtonState.Pressed &&
        Stylus.CurrentStylusDevice?.InAir != false && !TouchesOver.Any() && !TouchesCapturedWithin.Any() &&
        Keyboard.FocusedElement is not TextBox && !_pageMetadataSavePending && ViewModel.CanApplyNativeSync;

    private async Task RunNativeSync()
    {
        if (_nativeSyncRunning || !IsActive || (!ViewModel.NativeSyncRecoveryRequired && !NativeSyncIdle)) return;
        var store = ViewModel.Store;
        _nativeSyncRunning = true;
        using var foreground = CancellationTokenSource.CreateLinkedTokenSource(_nativeSyncLifetime.Token);
        _nativeSyncForeground = foreground;
        try
        {
            var configuration = NativeSyncConfiguration.Load(store.RootDirectory);
            if (!configuration.Enabled && !ViewModel.NativeSyncRecoveryRequired) { ResetNativeSyncClient(); ViewModel.NativeSyncStatus = "原生同步未启用"; return; }
            var key = (store, configuration.Endpoint, configuration.Token,
                System.Text.Json.JsonSerializer.Serialize(new { configuration.Addresses, configuration.CertificatePins }));
            if (_nativeSyncClient == null || _nativeSyncClientKey != key)
            {
                ResetNativeSyncClient();
                _nativeSyncClient = await Task.Run(() => new NativeSyncClient(store.RootDirectory, configuration), foreground.Token);
                _nativeSyncClientKey = key;
                var observedClient = _nativeSyncClient;
                observedClient.SnapshotChanged += snapshot => Dispatcher.BeginInvoke(() =>
                {
                    if (ReferenceEquals(store, ViewModel.Store) && ReferenceEquals(observedClient, _nativeSyncClient))
                        ViewModel.NativeSyncStatus = NativeSyncDisplayStatus(snapshot);
                });
            }
            var client = _nativeSyncClient;
            if (ViewModel.NativeSyncRecoveryRequired)
            {
                var recovered = await Task.Run(() => client.RecoverPendingIncomingAsync(action => Dispatcher.InvokeAsync(() =>
                    !foreground.IsCancellationRequested && IsActive && ReferenceEquals(store, ViewModel.Store) && ViewModel.TryRecoverNativeSync(action)).Task, foreground.Token), foreground.Token);
                if (recovered && !client.HasPendingIncoming && ViewModel.NativeSyncRecoveryRequired)
                    ViewModel.TryRecoverNativeSync(() => { });
                ViewModel.NativeSyncStatus = ViewModel.NativeSyncRecoveryRequired ? "接收恢复未完成，编辑已锁定：" + client.Status : "本地接收已完整恢复";
                return;
            }
            _nativeSyncPending = false;
            await Task.Run(() => client.SyncAsync(action => Dispatcher.InvokeAsync(() =>
            {
                if (foreground.IsCancellationRequested || !ReferenceEquals(store, ViewModel.Store) || !NativeSyncIdle) return false;
                return ViewModel.TryApplyNativeSync(action, () => ReferenceEquals(store, ViewModel.Store) && NativeSyncIdle);
            }).Task, foreground.Token), foreground.Token);
            if (ReferenceEquals(store, ViewModel.Store))
                ViewModel.NativeSyncStatus = ViewModel.NativeSyncRecoveryRequired ? "接收失败，编辑已锁定，等待本地完整恢复" :
                    _nativeSyncPending && client.Snapshot.Phase is NativeSyncPhase.Synced or NativeSyncPhase.Pending ? "本地已保存，待上传" : NativeSyncDisplayStatus(client.Snapshot);
        }
        catch (OperationCanceledException) { }
        catch (Exception error)
        {
            if (ReferenceEquals(store, ViewModel.Store)) ViewModel.NativeSyncStatus = "同步配置或本地恢复错误：" + error.Message;
        }
        finally
        {
            _nativeSyncForeground = null;
            _nativeSyncRunning = false;
            if (_nativeSyncLifetime.IsCancellationRequested) ResetNativeSyncClient();
            else if (foreground.IsCancellationRequested && IsActive) _ = Dispatcher.BeginInvoke(() => _ = RunNativeSync());
        }
    }

    private void ResetNativeSyncClient()
    {
        _nativeSyncClient?.Dispose();
        _nativeSyncClient = null;
        _nativeSyncClientKey = null;
    }

    private static string NativeSyncDisplayStatus(NativeSyncStatus status) => status.Phase switch
    {
        NativeSyncPhase.Offline => "离线 · " + status.Message,
        NativeSyncPhase.Conflict => "冲突 · " + status.Message,
        NativeSyncPhase.Error => "同步错误 · " + status.Message,
        _ => status.Message,
    };

    private void ShowNativeSyncSettings()
    {
        var root = ViewModel.WorkspaceRoot;
        NativeSyncConfiguration configuration;
        try { configuration = NativeSyncConfiguration.Load(root); }
        catch (Exception error) { MessageBox.Show(this, error.Message, "同步配置读取失败"); return; }
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(new TextBlock { Text = "原生同步仅用于明确创建的独立副本。请选择 Syncthing 文件夹之外的新目录；原笔记库保留。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock { Text = root, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) });
        panel.Children.Add(new TextBlock { Text = "服务器地址（每行一个，严格按顺序尝试）" });
        var endpoint = new TextBox { Text = string.Join(Environment.NewLine, configuration.ServerAddresses), AcceptsReturn = true,
            MinHeight = 82, MaxHeight = 180, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(0, 4, 0, 10) };
        panel.Children.Add(endpoint);
        panel.Children.Add(new TextBlock { Text = "访问令牌（仅保存在此电脑）" });
        var token = new PasswordBox { Password = configuration.Token, Margin = new Thickness(0, 4, 0, 10) };
        panel.Children.Add(token);
        var enabled = new CheckBox { Content = "启用此独立副本的前台自动同步", IsChecked = configuration.Enabled };
        panel.Children.Add(enabled);
        var status = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
        panel.Children.Add(status);
        var window = new Window { Owner = this, Title = "原生同步设置", Width = 540, SizeToContent = SizeToContent.Height, WindowStartupLocation = WindowStartupLocation.CenterOwner, Content = panel };
        var saveSettingsInProgress = false;
        window.Closing += (_, args) => { if (saveSettingsInProgress) args.Cancel = true; };
        var clone = new Button { Content = "创建并打开独立副本…", Margin = new Thickness(0, 6, 0, 6) };
        clone.Click += async (_, _) =>
        {
            var picker = new SaveFileDialog { Title = "输入尚不存在的副本目录名", FileName = "Mojian-native", CheckPathExists = true, AddExtension = false };
            if (picker.ShowDialog(window) != true) return;
            try
            {
                if (_nativeSyncRunning) throw new IOException("同步正在进行，请完成后再创建副本");
                clone.IsEnabled = false;
                saveSettingsInProgress = true;
                ViewModel.SaveAll();
                await Task.Run(() => NativeSyncClient.CloneWorkspace(root, picker.FileName));
                ViewModel.SwitchWorkspace(picker.FileName);
                saveSettingsInProgress = false;
                window.Close();
                ShowNativeSyncSettings();
            }
            catch (Exception error) { status.Text = "未启用副本：" + error.Message; }
            finally { clone.IsEnabled = true; saveSettingsInProgress = false; }
        };
        panel.Children.Add(clone);
        var save = new Button { Content = "保存设置", Margin = new Thickness(0, 6, 0, 0) };
        save.Click += (_, _) =>
        {
            try
            {
                if (_nativeSyncRunning || saveSettingsInProgress) throw new IOException("操作正在进行，请完成后再修改设置");
                var addresses = NativeSyncConfiguration.ParseAddresses(endpoint.Text);
                if (enabled.IsChecked == true)
                {
                    NativeSyncClient.RequireDedicatedWorkspace(root);
                    if (string.IsNullOrWhiteSpace(token.Password)) throw new IOException("请输入访问令牌");
                }
                new NativeSyncConfiguration { Endpoint = configuration.Endpoint, Addresses = addresses,
                    CertificatePins = configuration.CertificatePins, Token = token.Password, Enabled = enabled.IsChecked == true }.Save(root);
                ViewModel.NativeSyncStatus = enabled.IsChecked == true ? "等待前台同步" : "原生同步未启用";
                window.Close();
            }
            catch (Exception error) { status.Text = error.Message; }
        };
        panel.Children.Add(save);
        window.ShowDialog();
    }
}
