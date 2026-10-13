$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$proj = Join-Path $root "AhccDesktopViewer"
$exe = Join-Path $proj "bin\Release\net8.0-windows\AhccDesktopViewer.exe"

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class AhccDvWin {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
"@

$running = Get-Process -Name AhccDesktopViewer -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
    Select-Object -First 1
if ($running) {
    if ([AhccDvWin]::IsIconic($running.MainWindowHandle)) {
        [AhccDvWin]::ShowWindow($running.MainWindowHandle, 9) | Out-Null
    }
    [AhccDvWin]::SetForegroundWindow($running.MainWindowHandle) | Out-Null
    exit 0
}

if (-not (Test-Path $exe)) {
    Push-Location $proj
    try {
        & dotnet build -c Release
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    finally {
        Pop-Location
    }
}

Start-Process -FilePath $exe
