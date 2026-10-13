using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using AhccDesktopViewer.Services;

namespace AhccDesktopViewer;

public partial class FlashcardsWindow : Window
{
    private readonly ObservableCollection<FlashcardRow> _rows = new();
    private readonly DispatcherTimer _blankTimer;
    private bool _suppressSelection;
    private bool _fullscreen;
    private bool _blanked;
    private int _blankSeconds = 60;
    private int _idleSeconds;
    private WindowState _prevState;
    private WindowStyle _prevStyle;
    private ResizeMode _prevResize;
    private double _prevLeft;
    private double _prevTop;
    private double _prevWidth;
    private double _prevHeight;
    private bool _prevTopmost;
    private string _shownKey = "";

    public FlashcardsWindow()
    {
        InitializeComponent();
        HistoryList.ItemsSource = _rows;
        _blankTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _blankTimer.Tick += (_, _) => OnBlankTick();
        _blankTimer.Start();
    }

    public void ApplyBlankSeconds(int seconds)
    {
        _blankSeconds = Math.Max(0, seconds);
        _idleSeconds = 0;
        if (_blankSeconds == 0)
            Wake();
    }

    public void ShowCards(IReadOnlyList<FlashcardCard> cards, string? status)
    {
        if (!string.IsNullOrWhiteSpace(status))
            StatusText.Text = status;

        var newest = cards.Count == 0 ? null : cards[^1];
        var key = newest == null ? "" : newest.En + "\n" + newest.Ru;
        var arrived = key.Length > 0 && key != _shownKey;
        if (!arrived && _rows.Count == cards.Count) return;

        _suppressSelection = true;
        _rows.Clear();
        foreach (var card in cards)
            _rows.Add(new FlashcardRow(card.En, card.Ru));
        _suppressSelection = false;

        if (_rows.Count > 0)
        {
            HistoryList.SelectedIndex = _rows.Count - 1;
            HistoryList.ScrollIntoView(_rows[^1]);
            ShowCard(_rows[^1]);
            _shownKey = key;
            if (arrived)
                Wake();
        }
    }

    public void ToggleFullscreen()
    {
        if (_fullscreen)
            ExitFullscreen();
        else
            EnterFullscreen();
        Wake();
    }

    private void EnterFullscreen()
    {
        _prevState = WindowState;
        _prevStyle = WindowStyle;
        _prevResize = ResizeMode;
        _prevLeft = Left;
        _prevTop = Top;
        _prevWidth = Width;
        _prevHeight = Height;
        _prevTopmost = Topmost;

        WindowState = WindowState.Normal;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        Topmost = true;
        Left = SystemParameters.VirtualScreenLeft;
        Top = SystemParameters.VirtualScreenTop;
        Width = SystemParameters.VirtualScreenWidth;
        Height = SystemParameters.VirtualScreenHeight;
        _fullscreen = true;
        ApplyChrome();
    }

    private void ExitFullscreen()
    {
        Topmost = _prevTopmost;
        WindowStyle = _prevStyle;
        ResizeMode = _prevResize;
        Left = _prevLeft;
        Top = _prevTop;
        Width = _prevWidth;
        Height = _prevHeight;
        WindowState = _prevState;
        _fullscreen = false;
        ApplyChrome();
    }

    private void ApplyChrome()
    {
        var extra = _fullscreen ? Visibility.Collapsed : Visibility.Visible;
        TitleText.Visibility = extra;
        StatusText.Visibility = extra;
        HistoryCaption.Visibility = extra;
        HistoryList.Visibility = extra;
        EnglishText.FontSize = _fullscreen ? 72 : 32;
        RussianText.FontSize = _fullscreen ? 56 : 28;
    }

    private void OnBlankTick()
    {
        if (_blankSeconds <= 0 || _blanked) return;
        _idleSeconds++;
        if (_idleSeconds < _blankSeconds) return;
        _blanked = true;
        BlankOverlay.Visibility = Visibility.Visible;
    }

    private void Wake()
    {
        _idleSeconds = 0;
        _blanked = false;
        BlankOverlay.Visibility = Visibility.Collapsed;
    }

    private void Window_MouseMove(object sender, MouseEventArgs e)
    {
        if (_blanked) Wake();
        else _idleSeconds = 0;
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.None)
        {
            ToggleFullscreen();
            e.Handled = true;
            return;
        }
        Wake();
    }

    private void HistoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelection) return;
        if (HistoryList.SelectedItem is FlashcardRow row)
        {
            ShowCard(row);
            Wake();
        }
    }

    private void ShowCard(FlashcardRow row)
    {
        EnglishText.Text = row.En;
        RussianText.Text = row.Ru;
    }

    private sealed class FlashcardRow
    {
        public FlashcardRow(string en, string ru)
        {
            En = en;
            Ru = ru;
            Label = $"{en}  —  {ru}";
        }

        public string En { get; }
        public string Ru { get; }
        public string Label { get; }
    }
}
