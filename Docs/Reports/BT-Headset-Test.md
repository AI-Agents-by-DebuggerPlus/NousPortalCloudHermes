# AndroidHermesCloudChat — отчёт: тестирование BT-кнопок гарнитуры
#
# Версия приложения на момент отчёта: 1.1.3 (5)
# Образец захвата: AndroidChat — FGS mediaPlayback + MediaSessionCompat
# Образец жестов Play/Next: AndroidEnglishTutor — debounce + double-tap → Next

## Цель

Проверить, что приложение ловит нажатия media-кнопок Bluetooth-гарнитуры
(Play / Pause / Next / Previous / Stop / Headset Hook и др.) независимо от чата
с Hermes. Экран изолирован: счётчик, журнал и симуляция не зависят от сети.

## Как открыть тест

1. Запустить AHCC на устройстве.
2. Из чата (иконка гарнитуры) или из настроек открыть «Тест BT-кнопок».
3. Включить переключатель **Native capture**.
4. При первом включении система может запросить:
   - `POST_NOTIFICATIONS` (Android 13+) — чтобы показать уведомление FGS;
   - `BLUETOOTH_CONNECT` (Android 12+) — желательно для BT-стека.
5. Нажать кнопку на гарнитуре. Счётчик и журнал должны обновиться.
6. Если реального нажатия нет — поставить на паузу Spotify / YouTube / **BT_TestV1** /
   AndroidChat (другая активная MediaSession перехватывает кнопки). Симуляция на экране
   всегда работает локально и не зависит от BT.

См. также автономный стенд: [BT_TestV1](../../BT_TestV1/Docs/README.md) / [отчёт BT_TestV1](BT_TestV1.md).

Маршрут навигации: `bluetooth_test` (`AhccNavHost`).

## Архитектура

```
BluetoothTestScreen (Compose UI)
        │
        ▼
BluetoothTestViewModel  ──start/stop──►  HeadsetMonitorService (FGS)
        │                                         │
        │                                         │ MediaSessionCompat.Callback
        ▼                                         ▼
 HeadsetButtonHub  ◄──── notifyButton(label, source) ────┘
   StateFlow: счётчик, last, journal
   SharedFlow: события (на будущее)
```

| Компонент | Файл | Роль |
|-----------|------|------|
| UI | `presentation/bluetooth/BluetoothTestScreen.kt` | Switch capture, счётчик, симуляция, журнал |
| Hub | `headset/HeadsetButtonHub.kt` | Дебаунс 500 ms, нормализация меток, StateFlow |
| Имена кнопок | `headset/HeadsetButtonNames.kt` | `KeyEvent` → `MEDIA_*` / `HEADSETHOOK` |
| Захват | `headset/HeadsetMonitorService.kt` | FGS + MediaSession |
| Жизненный цикл приложения | `AhccApp.kt` | Один экземпляр `headsetHub` |
| Манифест | `AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, сервис `mediaPlayback` |

## Native capture (реальные нажатия)

Схема совпадает с AndroidChat:

1. **Foreground Service** типа `mediaPlayback` с постоянным уведомлением
   («мониторинг кнопок гарнитуры»).
2. **MediaSessionCompat** с флагами:
   - `FLAG_HANDLES_MEDIA_BUTTONS`
   - `FLAG_HANDLES_TRANSPORT_CONTROLS`
3. **PlaybackState** = `STATE_PAUSED` и набор actions
   (PLAY, PAUSE, PLAY_PAUSE, SKIP_*, STOP).
4. **Callback**:
   - `onPlay` / `onPause` / `onSkipToNext` / `onSkipToPrevious` / `onStop` —
     вызывают `hub.notifyButton("MEDIA_…")`;
   - `onMediaButtonEvent` — читает `EXTRA_KEY_EVENT`, маппит через
     `HeadsetButtonNames.fromKeyCode`, учитывает только `ACTION_DOWN`
     с `repeatCount == 0`.
5. При `onCreate` сервиса: `headsetHub.setCaptureOn(true)`;
   при `onDestroy`: `false` и `release()` сессии.

Симуляция **не** ходит в MediaSession: `hub.simulate(label)` →
`notifyButton(..., source = "ui-simulate")`. Поэтому UI-кнопки работают
даже при выключенном capture.

## Жесты Play / Next (как в AndroidEnglishTutor)

`HeadsetButtonHub` + `HeadsetButtonPreferences`:

| Жест | Результат |
|------|-----------|
| Одиночный Play/Pause/Hook | После окна `nextDoubleTapMs` (по умолчанию 400 ms) → счётчик **Play** |
| Второй Play-жест в окне | Отмена pending Play → счётчик **Next (2×Play)** |
| Аппаратный `MEDIA_NEXT` | Счётчик **Next**; companion Play подавляется на `nextDoubleTapMs` |
| Debounce после commit | Опционально, интервал по умолчанию 500 ms |

Настройки хранятся в SharedPreferences `ahcc_headset_button_prefs`.

## Состояние на экране

`HeadsetTestState`:

- `captureOn` — сервис запущен;
- `pressCount` / `nextCount` — счётчики Play и Next;
- `lastLabel` / `lastAt` / `lastKind` — последнее событие;
- `eventLog` — до 40 строк вида  
  `12:34:56.789  [HARDWARE]  Play  (#3)` или `Next (2×Play)`.

Вид в журнале:

- `HARDWARE` — реальное нажатие (`native` / MediaSession);
- `SIMULATED` — кнопка на экране теста.

## Разрешения и манифест

Объявлены:

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `POST_NOTIFICATIONS`
- `MODIFY_AUDIO_SETTINGS`
- `BLUETOOTH` (maxSdk 30), `BLUETOOTH_CONNECT`

Сервис:

```xml
<service
    android:name=".headset.HeadsetMonitorService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

Отдельный `MediaButtonReceiver` **не** используется (как в AndroidChat):
достаточно активной MediaSession у FGS.

## Отличия от неудачной промежуточной версии

Ранее пробовали silent `MediaPlayer`, audio focus, `STATE_PLAYING` и
`androidx.media.session.MediaButtonReceiver`. Для реальных кнопок это не
потребовалось. Рабочая схема — как в AndroidChat: простой FGS +
`STATE_PAUSED` + активная сессия.

## Проверка вручную

1. Capture ON → в шторке видно уведомление AHCC headset monitor.
2. Симулировать Play → счётчик +1, в журнале `(ui-simulate)`.
3. Нажать Play на гарнитуре (другие медиа на паузе) → +1, `(native)`.
4. Capture OFF → сервис останавливается, реальные кнопки больше не логируются;
   симуляция по-прежнему увеличивает счётчик.
5. Быстрые двойные нажатия одной и той же кнопки (< 500 ms) — второе
   отбрасывается дебаунсом.

## Связанные строки / иконки

- Канал уведомления: `R.string.bt_monitor_*`
- Иконка уведомления: `R.drawable.ic_launcher`
