# BT_TestV1

Отдельное Android-приложение для теста media-кнопок Bluetooth-гарнитуры.

- Документация: [Docs/README.md](Docs/README.md)
- Пакет: `com.bttest.v1`
- Версия: **1.0.1**

```bash
.\gradlew.bat :app:installDebug
adb shell am start -n com.bttest.v1/.MainActivity
```

При конфликте с AHCC:

```bash
adb shell am force-stop com.nous.ahcc
```

Затем в приложении: **Native capture ON** → **Reassert MediaSession**.
