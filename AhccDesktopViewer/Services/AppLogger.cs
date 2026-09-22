using System.Collections.Concurrent;
using System.IO;
using System.Text;

namespace AhccDesktopViewer.Services;

public static class AppLogger
{
    private static readonly object Gate = new();
    private static readonly ConcurrentQueue<string> Ring = new();
    private const int RingMax = 2000;

    public static string LogDirectory { get; } =
        Path.Combine(AppContext.BaseDirectory, "logs");

    public static string CurrentLogFile { get; private set; } =
        Path.Combine(LogDirectory, $"ahccdv-{DateTime.Now:yyyyMMdd-HHmmss}.log");

    public static event Action<string>? LineAdded;

    public static IReadOnlyList<string> Snapshot() => Ring.ToArray();

    public static void Info(string message) => Write("INFO", message);

    public static void Warn(string message) => Write("WARN", message);

    public static void Error(string message, Exception? ex = null)
    {
        Write("ERROR", message);
        if (ex != null)
            Write("ERROR", ex.ToString());
    }

    public static void Debug(string message) => Write("DEBUG", message);

    private static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {message}";
        lock (Gate)
        {
            Directory.CreateDirectory(LogDirectory);
            File.AppendAllText(CurrentLogFile, line + Environment.NewLine, Encoding.UTF8);
        }

        Ring.Enqueue(line);
        while (Ring.Count > RingMax && Ring.TryDequeue(out _)) { }

        try { LineAdded?.Invoke(line); }
        catch { /* ignore UI subscriber errors */ }
    }
}
