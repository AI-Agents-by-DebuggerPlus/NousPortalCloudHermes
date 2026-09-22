using System.Diagnostics;
using System.Reflection;
using System.Windows;
using AhccDesktopLauncher.Services;

namespace AhccDesktopLauncher;

public partial class MainWindow : Window
{
    private readonly LauncherSettings _settings;
    private AhccDesktopTarget? _current;
    private bool _suppressAutoStartSave;
    private readonly string _launcherVersion;

    public MainWindow()
    {
        InitializeComponent();
        _launcherVersion = ReadLauncherVersion();
        Title = $"AHCC Desktop Launcher v{_launcherVersion}";
        TitleText.Text = $"AHCC Desktop Launcher v{_launcherVersion}";

        _settings = LauncherSettings.Load();
        _suppressAutoStartSave = true;
        AutoStartCheckBox.IsChecked = _settings.AutoStartDesktop;
        _suppressAutoStartSave = false;
    }

    private static string ReadLauncherVersion()
    {
        var asm = Assembly.GetExecutingAssembly();
        var info = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(info))
        {
            var plus = info.IndexOf('+', StringComparison.Ordinal);
            return plus >= 0 ? info[..plus] : info;
        }

        return asm.GetName().Version?.ToString(3) ?? "1.0.0";
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshTarget();
        if (_settings.AutoStartDesktop)
        {
            LaunchDesktop(fromAutoStart: true);
        }
    }

    private void RefreshButton_Click(object sender, RoutedEventArgs e) => RefreshTarget();

    private void LaunchButton_Click(object sender, RoutedEventArgs e) => LaunchDesktop(fromAutoStart: false);

    private void AutoStartCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_suppressAutoStartSave) return;
        _settings.AutoStartDesktop = AutoStartCheckBox.IsChecked == true;
        _settings.Save();
        StatusText.Text = _settings.AutoStartDesktop
            ? "Start on launch enabled."
            : "Start on launch disabled.";
        StatusText.Foreground = System.Windows.Media.Brushes.LightSkyBlue;
    }

    private void RefreshTarget()
    {
        _current = AhccDesktopLocator.Find();
        if (_current is null)
        {
            TargetText.Text = "AndroidHermesCloudChat / desktop not found";
            PathText.Text = "Expected nearby: AndroidHermesCloudChat\\gradlew.bat";
            StatusText.Text = "No launch target available.";
            StatusText.Foreground = System.Windows.Media.Brushes.OrangeRed;
            LaunchButton.IsEnabled = false;
            return;
        }

        TargetText.Text = $"AHCC Desktop v{_current.DesktopVersion} · {_current.Mode}";
        PathText.Text = _current.DisplayPath;
        var visible = AhccDesktopLocator.FindVisibleDesktopProcess();
        StatusText.Text = visible is not null
            ? $"Launcher v{_launcherVersion} · Desktop is visible · {DateTime.Now:HH:mm:ss}"
            : $"Launcher v{_launcherVersion} · updated {DateTime.Now:HH:mm:ss}";
        StatusText.Foreground = System.Windows.Media.Brushes.LightSkyBlue;
        LaunchButton.IsEnabled = true;
    }

    private void LaunchDesktop(bool fromAutoStart)
    {
        RefreshTarget();
        if (_current is null) return;

        try
        {
            if (AhccDesktopLocator.TryActivateDesktopWindow())
            {
                StatusText.Text = fromAutoStart
                    ? "AHCC Desktop already running — brought to front."
                    : "AHCC Desktop already running — brought to front.";
                StatusText.Foreground = System.Windows.Media.Brushes.Khaki;
                return;
            }

            var started = Process.Start(_current.StartInfo);
            if (started is null)
            {
                StatusText.Text = "Failed to start AHCC Desktop process.";
                StatusText.Foreground = System.Windows.Media.Brushes.OrangeRed;
                return;
            }

            StatusText.Text = fromAutoStart
                ? $"Auto-start: AHCC Desktop v{_current.DesktopVersion}"
                : $"Started: AHCC Desktop v{_current.DesktopVersion} ({_current.Mode})";
            StatusText.Foreground = System.Windows.Media.Brushes.LightGreen;
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Launch error: {ex.Message}";
            StatusText.Foreground = System.Windows.Media.Brushes.OrangeRed;
        }
    }
}
