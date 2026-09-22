using System.IO;
using System.Text.Json;

namespace AhccDesktopViewer.Services;

public sealed class ViewerSettings
{
    public string SessionId { get; set; } = "";

    /// <summary>Supabase project URL, e.g. https://xxxx.supabase.co</summary>
    public string SupabaseUrl { get; set; } = "https://dauvhkttddxmqfkfunqg.supabase.co";

    /// <summary>Supabase anon / publishable key</summary>
    public string SupabaseAnonKey { get; set; } =
        "sb_publishable_D1-ieyE_Tskl6BUrOSJ7RA_x-9spRcz";

    /// <summary>Hermes / assistant message font size.</summary>
    public double ResponseFontSize { get; set; } = 18;

    /// <summary>User / request message font size.</summary>
    public double RequestFontSize { get; set; } = 16;

    /// <summary>Left inset for Live / fullscreen content (px).</summary>
    public double ContentPaddingLeft { get; set; } = 16;

    /// <summary>Top inset for Live / fullscreen content (px).</summary>
    public double ContentPaddingTop { get; set; } = 16;

    public string ResponseTextColor { get; set; } = "#E8F4F3";
    public string RequestTextColor { get; set; } = "#E8F4F3";
    public string BubbleBackgroundColor { get; set; } = "#1A4550";
    public string ChatAreaBackgroundColor { get; set; } = "#123D45";

    /// <summary>When true, Live + fullscreen use a flat SolidBackgroundColor (default black).</summary>
    public bool SolidBackground { get; set; }

    public string SolidBackgroundColor { get; set; } = "#000000";

    /// <summary>Message text only — no sender labels, bubble borders, or bubble fills.</summary>
    public bool TextOnlyMode { get; set; }

    public string FullscreenHotKey { get; set; } = "F";

    public bool AutoConnect { get; set; }

    /// <summary>Poll Supabase for new messages while connected (seconds). 0 = off.</summary>
    public int PollSeconds { get; set; } = 5;

    private static string SettingsPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "AhccDesktopViewer",
            "settings.json");

    public static ViewerSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return new ViewerSettings();
            var json = File.ReadAllText(SettingsPath);
            return JsonSerializer.Deserialize<ViewerSettings>(json) ?? new ViewerSettings();
        }
        catch (Exception ex)
        {
            AppLogger.Warn($"Settings load failed: {ex.Message}");
            return new ViewerSettings();
        }
    }

    public void Save()
    {
        try
        {
            var dir = Path.GetDirectoryName(SettingsPath);
            if (!string.IsNullOrWhiteSpace(dir)) Directory.CreateDirectory(dir);
            File.WriteAllText(
                SettingsPath,
                JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
            AppLogger.Info("Settings saved");
        }
        catch (Exception ex)
        {
            AppLogger.Error("Settings save failed", ex);
        }
    }
}
