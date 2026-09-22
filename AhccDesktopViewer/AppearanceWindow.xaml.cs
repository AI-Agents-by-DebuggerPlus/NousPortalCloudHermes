using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using AhccDesktopViewer.Services;

namespace AhccDesktopViewer;

public partial class AppearanceWindow : Window
{
    private static readonly string[] PresetHex =
    [
        "#000000", "#0B1F2A", "#123D45", "#1A4550", "#245560",
        "#E8F4F3", "#8AA8A6", "#FFFFFF", "#1FA6A0", "#FFD700",
        "#EF5350", "#4CAF50", "#42A5F5", "#AB47BC", "#FFA726",
        "#263238", "#37474F", "#455A64", "#90A4AE", "#CFD8DC",
    ];

    private readonly ViewerSettings _settings;
    private readonly Action _onLiveChanged;
    private readonly Action<string>? _onStatus;
    private readonly DispatcherTimer _toastTimer;
    private bool _ready;
    private bool _savedThisSession;

    public AppearanceWindow(ViewerSettings settings, Action onLiveChanged, Action<string>? onStatus = null)
    {
        InitializeComponent();
        _settings = settings;
        _onLiveChanged = onLiveChanged;
        _onStatus = onStatus;
        _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.5) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer.Stop();
            ToastBanner.Visibility = Visibility.Collapsed;
        };
        BuildPalettes();
        LoadFromSettings();
        _ready = true;
    }

    private void BuildPalettes()
    {
        FillPalette(ResponseTextPalette, hex =>
        {
            _settings.ResponseTextColor = hex;
            RefreshSwatches();
            NotifyLive();
        });
        FillPalette(RequestTextPalette, hex =>
        {
            _settings.RequestTextColor = hex;
            RefreshSwatches();
            NotifyLive();
        });
        FillPalette(BubbleBgPalette, hex =>
        {
            _settings.BubbleBackgroundColor = hex;
            RefreshSwatches();
            NotifyLive();
        });
        FillPalette(ChatAreaBgPalette, hex =>
        {
            _settings.ChatAreaBackgroundColor = hex;
            RefreshSwatches();
            NotifyLive();
        });
        FillPalette(SolidBgPalette, hex =>
        {
            _settings.SolidBackgroundColor = hex;
            RefreshSwatches();
            NotifyLive();
        });
    }

    private void FillPalette(WrapPanel panel, Action<string> onPick)
    {
        panel.Children.Clear();
        foreach (var hex in PresetHex)
        {
            var swatch = new Border
            {
                Width = 22,
                Height = 22,
                Margin = new Thickness(0, 0, 6, 6),
                CornerRadius = new CornerRadius(3),
                BorderBrush = BrushFromHex("#2A5A62"),
                BorderThickness = new Thickness(1),
                Background = BrushFromHex(hex),
                Cursor = Cursors.Hand,
                ToolTip = hex,
                Tag = hex,
            };
            swatch.MouseLeftButtonUp += (_, _) => onPick((string)swatch.Tag);
            panel.Children.Add(swatch);
        }
    }

    private void LoadFromSettings()
    {
        ResponseFontSlider.Value = Clamp(_settings.ResponseFontSize, 12, 100);
        RequestFontSlider.Value = Clamp(_settings.RequestFontSize, 12, 100);
        PaddingLeftSlider.Value = Clamp(_settings.ContentPaddingLeft, 0, 200);
        PaddingTopSlider.Value = Clamp(_settings.ContentPaddingTop, 0, 200);
        ResponseFontLabel.Text = $"{(int)ResponseFontSlider.Value} px";
        RequestFontLabel.Text = $"{(int)RequestFontSlider.Value} px";
        PaddingLeftLabel.Text = $"{(int)PaddingLeftSlider.Value} px";
        PaddingTopLabel.Text = $"{(int)PaddingTopSlider.Value} px";
        SolidBackgroundCheck.IsChecked = _settings.SolidBackground;
        TextOnlyCheck.IsChecked = _settings.TextOnlyMode;
        RefreshSwatches();
    }

    private void RefreshSwatches()
    {
        ResponseTextSwatch.Background = BrushFromHex(_settings.ResponseTextColor, "#E8F4F3");
        RequestTextSwatch.Background = BrushFromHex(_settings.RequestTextColor, "#E8F4F3");
        BubbleBgSwatch.Background = BrushFromHex(_settings.BubbleBackgroundColor, "#1A4550");
        ChatAreaBgSwatch.Background = BrushFromHex(_settings.ChatAreaBackgroundColor, "#123D45");
        SolidBgSwatch.Background = BrushFromHex(_settings.SolidBackgroundColor, "#000000");
    }

    private void Any_Changed(object sender, RoutedEventArgs e)
    {
        if (!_ready) return;
        _settings.ResponseFontSize = ResponseFontSlider.Value;
        _settings.RequestFontSize = RequestFontSlider.Value;
        _settings.ContentPaddingLeft = PaddingLeftSlider.Value;
        _settings.ContentPaddingTop = PaddingTopSlider.Value;
        _settings.SolidBackground = SolidBackgroundCheck.IsChecked == true;
        _settings.TextOnlyMode = TextOnlyCheck.IsChecked == true;
        ResponseFontLabel.Text = $"{(int)ResponseFontSlider.Value} px";
        RequestFontLabel.Text = $"{(int)RequestFontSlider.Value} px";
        PaddingLeftLabel.Text = $"{(int)PaddingLeftSlider.Value} px";
        PaddingTopLabel.Text = $"{(int)PaddingTopSlider.Value} px";
        NotifyLive();
    }

    private void PickResponseText_Click(object sender, RoutedEventArgs e) =>
        PickCustom(c => { _settings.ResponseTextColor = c; RefreshSwatches(); NotifyLive(); }, _settings.ResponseTextColor);

    private void PickRequestText_Click(object sender, RoutedEventArgs e) =>
        PickCustom(c => { _settings.RequestTextColor = c; RefreshSwatches(); NotifyLive(); }, _settings.RequestTextColor);

    private void PickBubbleBg_Click(object sender, RoutedEventArgs e) =>
        PickCustom(c => { _settings.BubbleBackgroundColor = c; RefreshSwatches(); NotifyLive(); }, _settings.BubbleBackgroundColor);

    private void PickChatAreaBg_Click(object sender, RoutedEventArgs e) =>
        PickCustom(c => { _settings.ChatAreaBackgroundColor = c; RefreshSwatches(); NotifyLive(); }, _settings.ChatAreaBackgroundColor);

    private void PickSolidBg_Click(object sender, RoutedEventArgs e) =>
        PickCustom(c => { _settings.SolidBackgroundColor = c; RefreshSwatches(); NotifyLive(); }, _settings.SolidBackgroundColor);

    private void PickCustom(Action<string> apply, string currentHex)
    {
        var dlg = new ColorPickDialog(currentHex) { Owner = this };
        if (dlg.ShowDialog() == true)
            apply(dlg.SelectedHex);
    }

    private void NotifyLive() => _onLiveChanged();

    private void ShowToast(string message)
    {
        ToastText.Text = message;
        ToastBanner.Visibility = Visibility.Visible;
        _toastTimer.Stop();
        _toastTimer.Start();
        _onStatus?.Invoke(message);
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        Any_Changed(sender, e);
        _settings.Save();
        _savedThisSession = true;
        ShowToast("Appearance settings saved");
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Window_Closed(object? sender, EventArgs e)
    {
        _settings.Save();
        if (!_savedThisSession)
            _onStatus?.Invoke("Appearance settings saved");
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

    private static double Clamp(double value, double min, double max) =>
        value < min ? min : value > max ? max : value;

    private static Brush BrushFromHex(string hex, string fallback = "#000000")
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
            return Brushes.Black;
        }
    }
}
