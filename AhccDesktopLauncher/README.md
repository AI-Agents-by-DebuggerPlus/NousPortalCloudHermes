# AHCC Desktop Launcher (WPF)

Небольшой WPF-лаунчер для Compose Desktop клиента AHCC.

- Версия лаунчера отображается в заголовке окна.
- Цель: `AndroidHermesCloudChat` → `gradlew :desktop:run` (или packaged `AHCC.exe`, если есть).
- Опция автозапуска AHCC Desktop.

```bash
cd AhccDesktopLauncher
dotnet run -c Release
```

Или двойной клик: `Launch-AhccDesktopLauncher.bat` в корне репозитория.
