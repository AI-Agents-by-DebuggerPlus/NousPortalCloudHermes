@echo off
setlocal EnableExtensions
title AHCC Desktop
cd /d "%~dp0"

if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
  )
)

if defined JAVA_HOME (
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

REM Avoid DirectX12 adapter crashes on older GPUs (Skiko falls back poorly for some drivers).
if not defined SKIKO_RENDER_API set "SKIKO_RENDER_API=OPENGL"

echo Starting AHCC Desktop...
call "%~dp0gradlew.bat" :desktop:run --quiet
set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" (
  echo.
  echo AHCC Desktop exited with code %EXITCODE%.
  pause
)
exit /b %EXITCODE%
