using System.Diagnostics;
using System.Text;
using System.Windows;
using AhccDesktopViewer.Services;

namespace AhccDesktopViewer;

public partial class LogWindow : Window
{
    public LogWindow()
    {
        InitializeComponent();
        PathText.Text = AppLogger.CurrentLogFile;
        foreach (var line in AppLogger.Snapshot())
            Append(line);
        AppLogger.LineAdded += OnLine;
        Closed += (_, _) => AppLogger.LineAdded -= OnLine;
    }

    private void OnLine(string line) =>
        Dispatcher.Invoke(() => Append(line));

    private void Append(string line)
    {
        LogBox.AppendText(line + Environment.NewLine);
        LogBox.ScrollToEnd();
    }

    private void CopyAll_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(LogBox.Text);
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Copy failed");
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = AppLogger.LogDirectory,
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Open folder failed");
        }
    }

    private void ClearView_Click(object sender, RoutedEventArgs e) => LogBox.Clear();
}
