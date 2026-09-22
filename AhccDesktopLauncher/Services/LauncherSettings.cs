using System.IO;
using System.Text.Json;

namespace AhccDesktopLauncher.Services;

public sealed class LauncherSettings
{
    public bool AutoStartDesktop { get; set; }

    private static string SettingsPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "AhccDesktopLauncher",
            "settings.json");

    public static LauncherSettings Load()
    {
        try
        {
            var path = SettingsPath;
            if (!File.Exists(path)) return new LauncherSettings();
            var json = File.ReadAllText(path);
            return JsonSerializer.Deserialize<LauncherSettings>(json) ?? new LauncherSettings();
        }
        catch
        {
            return new LauncherSettings();
        }
    }

    public void Save()
    {
        var path = SettingsPath;
        var dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(dir)) Directory.CreateDirectory(dir);
        File.WriteAllText(
            path,
            JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
