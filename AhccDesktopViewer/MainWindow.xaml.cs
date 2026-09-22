using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using AhccDesktopViewer.Services;
using Microsoft.Win32;

namespace AhccDesktopViewer;

public enum BubbleKind
{
    Request,
    Response,
    System,
}

public partial class MainWindow : Window
{
    private readonly ViewerSettings _settings;
    private readonly ObservableCollection<ChatBubble> _messages = new();
    private bool _fullscreen;
    private bool _connected;
    private WindowState _prevState;
    private WindowStyle _prevStyle;
    private ResizeMode _prevResize;
    private double _prevLeft;
    private double _prevTop;
    private double _prevWidth;
    private double _prevHeight;
    private bool _prevTopmost;
    private AppearanceWindow? _appearanceWindow;
    private LogWindow? _logWindow;
    private CancellationTokenSource? _connectCts;
    private DispatcherTimer? _pollTimer;
    private DispatcherTimer? _toastTimer;

    public MainWindow()
    {
        InitializeComponent();
        _settings = ViewerSettings.Load();
        MessagesList.ItemsSource = _messages;
        FsMessagesList.ItemsSource = _messages;
        ApplySettingsToUi();
        ApplyAppearance();
        FooterText.Text = $"Logs: {AppLogger.CurrentLogFile}";
        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.5) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer.Stop();
            ToastBanner.Visibility = Visibility.Collapsed;
        };
        AppLogger.Info("MainWindow ready (Supabase-only)");
    }

    private void ApplySettingsToUi()
    {
        SessionIdBox.Text = _settings.SessionId;
        SupabaseUrlBox.Text = _settings.SupabaseUrl;
        SupabaseKeyBox.Text = _settings.SupabaseAnonKey;
        PollSecondsBox.Text = _settings.PollSeconds.ToString();
        HotKeyBox.Text = string.IsNullOrWhiteSpace(_settings.FullscreenHotKey)
            ? "F"
            : _settings.FullscreenHotKey[..1].ToUpperInvariant();
        AutoConnectCheck.IsChecked = _settings.AutoConnect;
        RefreshBubbleStyles();
    }

    private void ReadSettingsFromUi()
    {
        _settings.SessionId = SessionIdBox.Text.Trim();
        _settings.SupabaseUrl = SupabaseUrlBox.Text.Trim();
        _settings.SupabaseAnonKey = SupabaseKeyBox.Text.Trim();
        _settings.FullscreenHotKey = string.IsNullOrWhiteSpace(HotKeyBox.Text)
            ? "F"
            : HotKeyBox.Text.Trim()[..1].ToUpperInvariant();
        _settings.AutoConnect = AutoConnectCheck.IsChecked == true;
        if (int.TryParse(PollSecondsBox.Text.Trim(), out var poll) && poll >= 0)
            _settings.PollSeconds = poll;
    }

    private static string NormalizeHex(string? raw, string fallback)
    {
        var s = (raw ?? "").Trim();
        if (string.IsNullOrWhiteSpace(s)) return fallback;
        if (!s.StartsWith('#')) s = "#" + s;
        try
        {
            _ = (Color)ColorConverter.ConvertFromString(s)!;
            return s.ToUpperInvariant();
        }
        catch
        {
            return fallback;
        }
    }

    private static Brush BrushFromHex(string hex, string fallback)
    {
        try
        {
            var c = (Color)ColorConverter.ConvertFromString(NormalizeHex(hex, fallback))!;
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }
        catch
        {
            var c = (Color)ColorConverter.ConvertFromString(fallback)!;
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }
    }

    private BubbleKind ClassifySender(string? sender)
    {
        if (string.IsNullOrWhiteSpace(sender) ||
            sender.Equals("System", StringComparison.OrdinalIgnoreCase))
            return BubbleKind.System;
        if (sender.Equals("Hermes", StringComparison.OrdinalIgnoreCase) ||
            sender.Equals(AhccSupabaseClient.SenderHermes, StringComparison.OrdinalIgnoreCase))
            return BubbleKind.Response;
        return BubbleKind.Request;
    }

    private ChatBubble MakeBubble(string sender, string text, string? fileId = null, string? fileName = null)
    {
        var kind = ClassifySender(sender);
        var bubble = new ChatBubble
        {
            Sender = sender,
            Text = text,
            Kind = kind,
            FileId = fileId,
            FileName = fileName,
        };
        ApplyStyleToBubble(bubble);
        return bubble;
    }

    private void ApplyStyleToBubble(ChatBubble bubble)
    {
        bubble.FontSize = bubble.Kind switch
        {
            BubbleKind.Request => _settings.RequestFontSize,
            BubbleKind.System => Math.Min(_settings.RequestFontSize, _settings.ResponseFontSize),
            _ => _settings.ResponseFontSize,
        };
        bubble.TextBrush = bubble.Kind switch
        {
            BubbleKind.Request => BrushFromHex(_settings.RequestTextColor, "#E8F4F3"),
            _ => BrushFromHex(_settings.ResponseTextColor, "#E8F4F3"),
        };

        if (_settings.TextOnlyMode)
        {
            bubble.BubbleBrush = Brushes.Transparent;
            bubble.SenderVisibility = Visibility.Collapsed;
            bubble.BubbleCornerRadius = new CornerRadius(0);
            bubble.BubblePadding = new Thickness(0);
            bubble.BubbleMargin = new Thickness(0, 0, 0, 12);
        }
        else
        {
            bubble.BubbleBrush = BrushFromHex(_settings.BubbleBackgroundColor, "#1A4550");
            bubble.SenderVisibility = Visibility.Visible;
            bubble.BubbleCornerRadius = new CornerRadius(10);
            bubble.BubblePadding = new Thickness(12);
            bubble.BubbleMargin = new Thickness(0, 0, 0, 10);
        }
    }

    private void RefreshBubbleStyles()
    {
        foreach (var m in _messages)
            ApplyStyleToBubble(m);
    }

    private void ApplyAppearance()
    {
        if (LivePanelBorder is null || FullscreenOverlay is null || RootGrid is null) return;

        var padLeft = Math.Max(0, _settings.ContentPaddingLeft);
        var padTop = Math.Max(0, _settings.ContentPaddingTop);
        var contentPad = new Thickness(padLeft, padTop, padLeft, padTop);

        if (_settings.SolidBackground)
        {
            var solid = BrushFromHex(_settings.SolidBackgroundColor, "#000000");
            Background = solid;
            RootGrid.Margin = new Thickness(0);
            LivePanelBorder.Background = solid;
            LivePanelBorder.CornerRadius = new CornerRadius(0);
            LivePanelBorder.Padding = contentPad;
            LivePanelBorder.Margin = new Thickness(0);
            LivePanelBorder.BorderThickness = new Thickness(0);
            FullscreenOverlay.Background = solid;
            FullscreenOverlay.Padding = contentPad;
            FullscreenOverlay.BorderThickness = new Thickness(0);
        }
        else
        {
            Background = BrushFromHex("#0B1F2A", "#0B1F2A");
            RootGrid.Margin = new Thickness(16);
            LivePanelBorder.Background = BrushFromHex(_settings.ChatAreaBackgroundColor, "#123D45");
            LivePanelBorder.CornerRadius = new CornerRadius(10);
            LivePanelBorder.Padding = contentPad;
            LivePanelBorder.Margin = new Thickness(0, 8, 0, 0);
            LivePanelBorder.BorderThickness = new Thickness(0);
            FullscreenOverlay.Background = BrushFromHex(_settings.ChatAreaBackgroundColor, "#0B1F2A");
            FullscreenOverlay.Padding = contentPad;
            FullscreenOverlay.BorderThickness = new Thickness(0);
        }
    }

    private void Appearance_Changed(object sender, RoutedEventArgs e)
    {
        ApplyAppearance();
        RefreshBubbleStyles();
    }

    public void NotifyStatus(string message)
    {
        StatusText.Text = message;
        ShowToast(message);
    }

    private void ShowToast(string message)
    {
        ToastText.Text = message;
        ToastBanner.Visibility = Visibility.Visible;
        _toastTimer?.Stop();
        _toastTimer?.Start();
    }

    private void Appearance_Click(object sender, RoutedEventArgs e)
    {
        if (_appearanceWindow is { IsVisible: true })
        {
            _appearanceWindow.Activate();
            return;
        }

        _appearanceWindow = new AppearanceWindow(
            _settings,
            onLiveChanged: () =>
            {
                ApplyAppearance();
                RefreshBubbleStyles();
            },
            onStatus: msg =>
            {
                StatusText.Text = msg;
                // Toast already shown in AppearanceWindow; keep status line only.
            })
        {
            Owner = this,
        };
        _appearanceWindow.Closed += (_, _) => _appearanceWindow = null;
        _appearanceWindow.Show();
    }

    private void SaveSettings_Click(object sender, RoutedEventArgs e)
    {
        ReadSettingsFromUi();
        _settings.Save();
        ApplyAppearance();
        RefreshBubbleStyles();
        if (_connected) StartPoll();
        NotifyStatus("Settings saved");
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (_settings.AutoConnect)
            await ConnectAsync();
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        StopPoll();
        _connectCts?.Cancel();
        _logWindow?.Close();
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_connected)
        {
            DisconnectUi();
            return;
        }

        await ConnectAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e)
    {
        if (!_connected) return;
        await ReloadHistoryAsync();
    }

    private void DisconnectUi()
    {
        StopPoll();
        _connectCts?.Cancel();
        _connected = false;
        StatusText.Text = "Disconnected";
        ConnectButton.Content = "Connect";
        AppLogger.Info("Disconnected");
    }

    private async Task ConnectAsync()
    {
        ReadSettingsFromUi();
        _settings.Save();
        _connectCts?.Cancel();
        _connectCts = new CancellationTokenSource();
        ConnectButton.IsEnabled = false;
        StatusText.Text = "Connecting…";
        _messages.Clear();

        try
        {
            using var sb = new AhccSupabaseClient(_settings.SupabaseUrl, _settings.SupabaseAnonKey);
            if (!sb.IsConfigured)
                throw new InvalidOperationException("Set Supabase URL and anon key in Settings.");

            StatusText.Text = "Supabase: resolving last session…";
            var last = await sb.FetchLastSessionIdAsync(_connectCts.Token);
            if (string.IsNullOrWhiteSpace(last))
                throw new InvalidOperationException(
                    "Supabase ahcc_messages is empty — connect AHCC/AHCCD first to create a session.");

            _settings.SessionId = last;
            SessionIdBox.Text = last;
            _settings.Save();
            AppLogger.Info($"Supabase last session_id={last}");

            await LoadHistoryIntoUiAsync(sb, last, _connectCts.Token);

            _connected = true;
            ConnectButton.Content = "Disconnect";
            StatusText.Text = $"Connected · Supabase session {last}";
            StartPoll();
        }
        catch (Exception ex)
        {
            AppLogger.Error("Connect failed", ex);
            StatusText.Text = $"Connect failed: {ex.Message}";
            MessageBox.Show(ex.Message, "AHCCDV Connect", MessageBoxButton.OK, MessageBoxImage.Warning);
            _connected = false;
            ConnectButton.Content = "Connect";
        }
        finally
        {
            ConnectButton.IsEnabled = true;
            ChatScroll.ScrollToEnd();
            FsScroll.ScrollToEnd();
        }
    }

    private async Task ReloadHistoryAsync()
    {
        if (string.IsNullOrWhiteSpace(_settings.SessionId)) return;
        try
        {
            using var sb = new AhccSupabaseClient(_settings.SupabaseUrl, _settings.SupabaseAnonKey);
            await LoadHistoryIntoUiAsync(sb, _settings.SessionId, CancellationToken.None);
            StatusText.Text = $"Refreshed · session {_settings.SessionId} · {_messages.Count} items";
            ChatScroll.ScrollToEnd();
            FsScroll.ScrollToEnd();
        }
        catch (Exception ex)
        {
            AppLogger.Error("Refresh failed", ex);
            StatusText.Text = $"Refresh failed: {ex.Message}";
        }
    }

    private async Task LoadHistoryIntoUiAsync(
        AhccSupabaseClient sb,
        string sessionId,
        CancellationToken ct)
    {
        var rows = await sb.FetchSessionMessagesAsync(sessionId, ct);
        _messages.Clear();
        foreach (var row in rows)
        {
            if (string.Equals(row.Content, AhccSupabaseClient.NewSessionContent, StringComparison.OrdinalIgnoreCase))
            {
                _messages.Add(MakeBubble("System", $"Session {row.SessionId}"));
                continue;
            }

            if (AhccSupabaseClient.TryParseFileMarker(row.Content, out var fileRef) && fileRef != null)
            {
                var sender = string.IsNullOrWhiteSpace(row.SenderName) ? "…" : row.SenderName;
                _messages.Add(MakeBubble(sender, $"[file] {fileRef.Name}", fileRef.Id, fileRef.Name));
                continue;
            }

            _messages.Add(MakeBubble(
                string.IsNullOrWhiteSpace(row.SenderName) ? "…" : row.SenderName,
                row.Content));
        }

        AppLogger.Info($"Supabase history rows={rows.Count}");
    }

    private void StartPoll()
    {
        StopPoll();
        if (_settings.PollSeconds <= 0) return;
        _pollTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(Math.Max(2, _settings.PollSeconds)),
        };
        _pollTimer.Tick += async (_, _) =>
        {
            if (!_connected) return;
            await ReloadHistoryAsync();
        };
        _pollTimer.Start();
        AppLogger.Info($"Poll every {_settings.PollSeconds}s");
    }

    private void StopPoll()
    {
        if (_pollTimer == null) return;
        _pollTimer.Stop();
        _pollTimer = null;
    }

    private async void Bubble_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: ChatBubble bubble }) return;
        if (string.IsNullOrWhiteSpace(bubble.FileId)) return;

        try
        {
            using var sb = new AhccSupabaseClient(_settings.SupabaseUrl, _settings.SupabaseAnonKey);
            var file = await sb.FetchFileAsync(bubble.FileId);
            if (file == null)
            {
                MessageBox.Show("File not found in Supabase.", "AHCCDV", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var dlg = new SaveFileDialog
            {
                FileName = string.IsNullOrWhiteSpace(file.FileName) ? "note.md" : file.FileName,
                Filter = "Markdown (*.md)|*.md|Text (*.txt)|*.txt|All files (*.*)|*.*",
            };
            if (dlg.ShowDialog(this) != true) return;
            await File.WriteAllTextAsync(dlg.FileName, file.Content ?? "");
            AppLogger.Info($"Saved file {dlg.FileName}");
            if (MessageBox.Show("Open file?", "AHCCDV", MessageBoxButton.YesNo, MessageBoxImage.Question) ==
                MessageBoxResult.Yes)
            {
                Process.Start(new ProcessStartInfo(dlg.FileName) { UseShellExecute = true });
            }
        }
        catch (Exception ex)
        {
            AppLogger.Error("Open file failed", ex);
            MessageBox.Show(ex.Message, "AHCCDV File", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void Logs_Click(object sender, RoutedEventArgs e)
    {
        if (_logWindow is { IsVisible: true })
        {
            _logWindow.Activate();
            return;
        }

        _logWindow = new LogWindow { Owner = this };
        _logWindow.Show();
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        var expected = string.IsNullOrWhiteSpace(_settings.FullscreenHotKey) ? "F" : _settings.FullscreenHotKey;
        if (e.Key.ToString().Equals(expected, StringComparison.OrdinalIgnoreCase) &&
            Keyboard.Modifiers == ModifierKeys.None)
        {
            ToggleFullscreen();
            e.Handled = true;
        }
    }

    private void ToggleFullscreen()
    {
        if (!_fullscreen)
        {
            _prevState = WindowState;
            _prevStyle = WindowStyle;
            _prevResize = ResizeMode;
            _prevLeft = Left;
            _prevTop = Top;
            _prevWidth = Width;
            _prevHeight = Height;
            _prevTopmost = Topmost;

            // Exclusive fullscreen: cover entire monitor including taskbar.
            WindowState = WindowState.Normal;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            Topmost = true;
            Left = SystemParameters.VirtualScreenLeft;
            Top = SystemParameters.VirtualScreenTop;
            Width = SystemParameters.VirtualScreenWidth;
            Height = SystemParameters.VirtualScreenHeight;

            FullscreenOverlay.Visibility = Visibility.Visible;
            MainTabs.Visibility = Visibility.Collapsed;
            FooterText.Visibility = Visibility.Collapsed;
            // Hide chrome header while fullscreen (title / Connect row).
            if (FindName("ChromeBar") is UIElement chrome)
                chrome.Visibility = Visibility.Collapsed;

            _fullscreen = true;
            AppLogger.Info("Fullscreen ON (exclusive)");
        }
        else
        {
            FullscreenOverlay.Visibility = Visibility.Collapsed;
            MainTabs.Visibility = Visibility.Visible;
            FooterText.Visibility = Visibility.Visible;
            if (FindName("ChromeBar") is UIElement chrome)
                chrome.Visibility = Visibility.Visible;

            Topmost = _prevTopmost;
            WindowStyle = _prevStyle;
            ResizeMode = _prevResize;
            Left = _prevLeft;
            Top = _prevTop;
            Width = _prevWidth;
            Height = _prevHeight;
            WindowState = _prevState;
            _fullscreen = false;
            AppLogger.Info("Fullscreen OFF");
        }
    }
}

public sealed class ChatBubble : INotifyPropertyChanged
{
    private string _text = "";
    private string _sender = "Hermes";
    private double _fontSize = 18;
    private Brush _textBrush = Brushes.White;
    private Brush _bubbleBrush = Brushes.DarkSlateGray;
    private Visibility _senderVisibility = Visibility.Visible;
    private CornerRadius _bubbleCornerRadius = new(10);
    private Thickness _bubblePadding = new(12);
    private Thickness _bubbleMargin = new(0, 0, 0, 10);

    public BubbleKind Kind { get; set; } = BubbleKind.Response;

    public string Text
    {
        get => _text;
        set { _text = value; OnPropertyChanged(); }
    }

    public string Sender
    {
        get => _sender;
        set { _sender = value; OnPropertyChanged(); }
    }

    public double FontSize
    {
        get => _fontSize;
        set { _fontSize = value; OnPropertyChanged(); }
    }

    public Brush TextBrush
    {
        get => _textBrush;
        set { _textBrush = value; OnPropertyChanged(); }
    }

    public Brush BubbleBrush
    {
        get => _bubbleBrush;
        set { _bubbleBrush = value; OnPropertyChanged(); }
    }

    public Visibility SenderVisibility
    {
        get => _senderVisibility;
        set { _senderVisibility = value; OnPropertyChanged(); }
    }

    public CornerRadius BubbleCornerRadius
    {
        get => _bubbleCornerRadius;
        set { _bubbleCornerRadius = value; OnPropertyChanged(); }
    }

    public Thickness BubblePadding
    {
        get => _bubblePadding;
        set { _bubblePadding = value; OnPropertyChanged(); }
    }

    public Thickness BubbleMargin
    {
        get => _bubbleMargin;
        set { _bubbleMargin = value; OnPropertyChanged(); }
    }

    public string? FileId { get; set; }
    public string? FileName { get; set; }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
