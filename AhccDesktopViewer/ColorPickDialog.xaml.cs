using System.Windows;
using System.Windows.Media;

namespace AhccDesktopViewer;

public partial class ColorPickDialog : Window
{
    private bool _ready;

    public string SelectedHex { get; private set; } = "#FFFFFF";

    public ColorPickDialog(string currentHex)
    {
        InitializeComponent();
        SelectedHex = Normalize(currentHex);
        var c = (Color)ColorConverter.ConvertFromString(SelectedHex)!;
        RSlider.Value = c.R;
        GSlider.Value = c.G;
        BSlider.Value = c.B;
        UpdatePreview();
        _ready = true;
    }

    private void Slider_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!_ready) return;
        UpdatePreview();
    }

    private void UpdatePreview()
    {
        var r = (byte)RSlider.Value;
        var g = (byte)GSlider.Value;
        var b = (byte)BSlider.Value;
        RLabel.Text = r.ToString();
        GLabel.Text = g.ToString();
        BLabel.Text = b.ToString();
        SelectedHex = $"#{r:X2}{g:X2}{b:X2}";
        Preview.Background = new SolidColorBrush(Color.FromRgb(r, g, b));
    }

    private void Ok_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
        Close();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private static string Normalize(string? raw)
    {
        var s = (raw ?? "#FFFFFF").Trim();
        if (!s.StartsWith('#')) s = "#" + s;
        try
        {
            _ = (Color)ColorConverter.ConvertFromString(s)!;
            return s.ToUpperInvariant();
        }
        catch
        {
            return "#FFFFFF";
        }
    }
}
