@echo off
setlocal
cd /d "%~dp0AhccDesktopViewer"
dotnet run -c Release --no-restore 2>nul
if errorlevel 1 (
  dotnet build -c Release
  if errorlevel 1 (
    echo Build failed.
    pause
    exit /b 1
  )
  dotnet run -c Release --no-build
)
