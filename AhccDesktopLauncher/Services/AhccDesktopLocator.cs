using System.Diagnostics;
using System.IO;
using System.Text.RegularExpressions;

namespace AhccDesktopLauncher.Services;

public sealed record AhccDesktopTarget(
    ProcessStartInfo StartInfo,
    string DesktopVersion,
    string Mode,
    string DisplayPath);

public static class AhccDesktopLocator
{
    private static readonly Regex PackageVersionRegex = new(
        @"ahccDesktopVersion\s*=\s*""([^""]+)""|packageVersion\s*=\s*""([^""]+)""",
        RegexOptions.Compiled);

    private static readonly string[] JavaHomeCandidates =
    [
        @"C:\Program Files\Android\Android Studio\jbr",
        @"C:\Program Files\Android\Android Studio1\jbr",
        @"C:\Program Files\JetBrains\IntelliJ IDEA\jbr",
    ];

    public static AhccDesktopTarget? Find()
    {
        var projectRoot = FindAndroidHermesCloudChatRoot();
        if (projectRoot is null) return null;

        var version = ReadDesktopVersion(projectRoot);
        var packagedExe = FindPackagedExe(projectRoot);
        if (packagedExe is not null)
        {
            return new AhccDesktopTarget(
                StartInfo: new ProcessStartInfo
                {
                    FileName = packagedExe,
                    WorkingDirectory = Path.GetDirectoryName(packagedExe) ?? projectRoot,
                    UseShellExecute = true,
                },
                DesktopVersion: version,
                Mode: "packaged",
                DisplayPath: packagedExe);
        }

        // Prefer silent VBS so closing a console window does not kill Desktop.
        var launchVbs = Path.Combine(projectRoot, "Launch-AHCC-Desktop.vbs");
        if (File.Exists(launchVbs))
        {
            return new AhccDesktopTarget(
                StartInfo: new ProcessStartInfo
                {
                    FileName = "wscript.exe",
                    Arguments = "\"" + launchVbs + "\"",
                    WorkingDirectory = projectRoot,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                },
                DesktopVersion: version,
                Mode: "Launch-AHCC-Desktop.vbs",
                DisplayPath: launchVbs);
        }

        // Fallback bat: sets JAVA_HOME (Explorer shortcuts often have none) and pauses on error.
        var launchBat = Path.Combine(projectRoot, "Launch-AHCC-Desktop.bat");
        if (File.Exists(launchBat))
        {
            return new AhccDesktopTarget(
                StartInfo: new ProcessStartInfo
                {
                    FileName = launchBat,
                    WorkingDirectory = projectRoot,
                    UseShellExecute = true,
                },
                DesktopVersion: version,
                Mode: "Launch-AHCC-Desktop.bat",
                DisplayPath: launchBat);
        }

        var gradlew = Path.Combine(projectRoot, "gradlew.bat");
        if (!File.Exists(gradlew)) return null;

        return new AhccDesktopTarget(
            StartInfo: CreateGradleStartInfo(gradlew, projectRoot),
            DesktopVersion: version,
            Mode: "gradle :desktop:run",
            DisplayPath: gradlew);
    }

    public static bool IsDesktopAlreadyRunning() => FindVisibleDesktopProcess() is not null;

    /// <summary>
    /// Visible AHCC Desktop window only — ignores Cursor tabs named Launch-AHCC-*.bat
    /// and headless/zombie java processes without a Desktop window.
    /// </summary>
    public static Process? FindVisibleDesktopProcess()
    {
        foreach (var name in new[] { "AHCC", "AhccDesktop" })
        {
            foreach (var process in Process.GetProcessesByName(name))
            {
                try
                {
                    if (process.MainWindowHandle != IntPtr.Zero &&
                        !string.IsNullOrWhiteSpace(process.MainWindowTitle))
                    {
                        return process;
                    }
                }
                catch
                {
                    // ignore
                }
            }
        }

        foreach (var process in Process.GetProcesses())
        {
            try
            {
                var title = process.MainWindowTitle;
                if (string.IsNullOrWhiteSpace(title)) continue;
                if (title.Contains("AHCC Desktop", StringComparison.OrdinalIgnoreCase) &&
                    !title.Contains("Launcher", StringComparison.OrdinalIgnoreCase) &&
                    process.MainWindowHandle != IntPtr.Zero)
                {
                    return process;
                }
            }
            catch
            {
                // ignore processes we cannot query
            }
        }

        return null;
    }

    public static bool TryActivateDesktopWindow()
    {
        var process = FindVisibleDesktopProcess();
        if (process is null || process.MainWindowHandle == IntPtr.Zero) return false;
        return NativeMethods.ShowAndForeground(process.MainWindowHandle);
    }

    private static class NativeMethods
    {
        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        private const int SwRestore = 9;

        public static bool ShowAndForeground(IntPtr hwnd)
        {
            ShowWindow(hwnd, SwRestore);
            return SetForegroundWindow(hwnd);
        }
    }

    private static ProcessStartInfo CreateGradleStartInfo(string gradlew, string projectRoot)
    {
        var javaHome = ResolveJavaHome();
        if (string.IsNullOrWhiteSpace(javaHome))
        {
            return new ProcessStartInfo
            {
                FileName = gradlew,
                Arguments = ":desktop:run",
                WorkingDirectory = projectRoot,
                UseShellExecute = true,
            };
        }

        // ShellExecute cannot set Environment; cmd wrapper injects JAVA_HOME.
        return new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments =
                "/c \"set \"JAVA_HOME=" + javaHome +
                "\" && set \"PATH=%JAVA_HOME%\\bin;%PATH%\" && cd /d \"" + projectRoot +
                "\" && call \"" + gradlew + "\" :desktop:run\"",
            WorkingDirectory = projectRoot,
            UseShellExecute = false,
            CreateNoWindow = false,
        };
    }

    private static string? ResolveJavaHome()
    {
        var fromEnv = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(fromEnv) &&
            File.Exists(Path.Combine(fromEnv, "bin", "java.exe")))
        {
            return fromEnv;
        }

        foreach (var candidate in JavaHomeCandidates)
        {
            if (File.Exists(Path.Combine(candidate, "bin", "java.exe")))
                return candidate;
        }

        return null;
    }

    private static string? FindAndroidHermesCloudChatRoot()
    {
        foreach (var root in EnumerateSearchRoots())
        {
            var candidate = Path.Combine(root, "AndroidHermesCloudChat");
            if (File.Exists(Path.Combine(candidate, "gradlew.bat")) &&
                Directory.Exists(Path.Combine(candidate, "desktop")))
            {
                return candidate;
            }

            if (Path.GetFileName(root).Equals("AndroidHermesCloudChat", StringComparison.OrdinalIgnoreCase) &&
                File.Exists(Path.Combine(root, "gradlew.bat")))
            {
                return root;
            }
        }

        return null;
    }

    private static IEnumerable<string> EnumerateSearchRoots()
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var start in new[]
                 {
                     AppContext.BaseDirectory,
                     Directory.GetCurrentDirectory(),
                     Path.GetDirectoryName(Environment.ProcessPath) ?? string.Empty,
                 })
        {
            if (string.IsNullOrWhiteSpace(start)) continue;
            var dir = new DirectoryInfo(Path.GetFullPath(start));
            for (var i = 0; i < 10 && dir is not null; i++, dir = dir.Parent)
            {
                if (!seen.Add(dir.FullName)) continue;
                yield return dir.FullName;
            }
        }
    }

    private static string ReadDesktopVersion(string projectRoot)
    {
        try
        {
            var gradle = Path.Combine(projectRoot, "desktop", "build.gradle.kts");
            if (!File.Exists(gradle)) return "unknown";
            var text = File.ReadAllText(gradle);
            var m = PackageVersionRegex.Match(text);
            if (!m.Success) return "unknown";
            if (m.Groups[1].Success && m.Groups[1].Length > 0) return m.Groups[1].Value;
            return m.Groups[2].Value;
        }
        catch
        {
            return "unknown";
        }
    }

    private static string? FindPackagedExe(string projectRoot)
    {
        var binaries = Path.Combine(projectRoot, "desktop", "build", "compose", "binaries");
        if (!Directory.Exists(binaries)) return null;

        return Directory.EnumerateFiles(binaries, "AHCC.exe", SearchOption.AllDirectories)
            .OrderByDescending(p => new FileInfo(p).LastWriteTimeUtc)
            .FirstOrDefault();
    }
}
