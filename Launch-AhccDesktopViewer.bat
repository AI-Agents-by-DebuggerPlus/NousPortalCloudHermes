@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Launch-AhccDesktopViewer.ps1"
if errorlevel 1 (
  echo AHCCDV failed to start.
  pause
  exit /b 1
)
