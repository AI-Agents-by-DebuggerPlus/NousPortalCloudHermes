# Отчёт: BT_TestV1

Отдельное приложение `BT_TestV1` (`com.bttest.v1`, **1.0.1**) выделено из AHCC для автономного теста BT-кнопок и переноса в другие проекты.

Полная документация: [../../BT_TestV1/Docs/README.md](../../BT_TestV1/Docs/README.md)

## Кратко

- FGS `mediaPlayback` + `MediaSessionCompat` (как AndroidChat / AHCC)
- Double-tap Play → Next, suppress companion Play (как AndroidEnglishTutor / AHCC)
- **v1.0.1:** AudioFocus + USAGE_MEDIA pulse + **Reassert** для claim media-button session
- Портативный пакет `com.bttest.v1.headset`

## Известный конфликт

Если одновременно запущен AHCC с Native capture, `dumpsys media_session` показывает  
`Media button session is com.nous.ahcc/AhccHeadset` — аппаратные кнопки не доходят до BT_TestV1 (симуляция при этом работает). Решение: `force-stop com.nous.ahcc` + Reassert в BT_TestV1.
