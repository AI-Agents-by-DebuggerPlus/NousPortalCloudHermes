# BT_TestV1 — тестовое приложение кнопок Bluetooth-гарнитуры

**Пакет:** `com.bttest.v1`  
**Версия:** 1.0.1 (2)  
**Источник логики:** AndroidHermesCloudChat (AHCC) — захват как AndroidChat; жесты Play/Next и claim session как AndroidEnglishTutor.

Отдельное приложение без чата Hermes: только экран теста BT-кнопок. Пакет `headset/` можно переносить в другие проекты.

---

## Быстрый старт

```bash
cd BT_TestV1
.\gradlew.bat :app:installDebug
adb shell am start -n com.bttest.v1/.MainActivity
```

1. Запустить **BT_TestV1**.
2. **Force-stop** конкурентов, если они держат MediaSession:
   ```bash
   adb shell am force-stop com.nous.ahcc
   adb shell am force-stop com.google.android.youtube
   ```
3. Включить **Native capture** (разрешить уведомления / BT Connect при запросе).
4. При необходимости нажать **Reassert MediaSession** (короткий USAGE_MEDIA pulse + claim).
5. Нажать Play на гарнитуре → счётчик **Play**, в журнале `[HARDWARE]`.
6. Двойной Play (или жест Next на Pixel Buds) → счётчик **Next**.
7. **Симулировать Play** дважды быстро → `Next (2×Play)` без гарнитуры.

Проверка media-button session:

```bash
adb shell dumpsys media_session
# Ожидание: Media button session is com.bttest.v1/.../BtTestV1Headset
```

Если видите `com.nous.ahcc/AhccHeadset` — кнопки уходят в AHCC, не в BT_TestV1.

---

## Архитектура

```
BluetoothTestScreen (Compose)
        │
        ▼
BluetoothTestViewModel ──start/reassert/stop──► HeadsetMonitorService (FGS mediaPlayback)
        │                                         │
        │                                         │ AudioFocus + PLAYING→PAUSED claim
        │                                         │ MediaPlaybackPulse (USAGE_MEDIA ~180 ms)
        │                                         │ MediaSessionCompat.Callback
        ▼                                         ▼
 HeadsetButtonHub  ◄──── notifyButton(label, source)
   • Play / Next counters
   • debounce + double-tap → Next
   • SharedFlow событий
```

| Файл | Роль |
|------|------|
| `headset/HeadsetMonitorService.kt` | FGS + MediaSession + AudioFocus + reassert |
| `headset/MediaPlaybackPulse.kt` | USAGE_MEDIA pulse для claim session |
| `headset/HeadsetButtonHub.kt` | Жесты, счётчики, журнал |
| `headset/HeadsetButtonPreferences.kt` | SharedPreferences debounce / Next window |
| `headset/HeadsetButtonNames.kt` | KeyCode → метка |
| `ui/BluetoothTestScreen.kt` | UI теста (+ ViewModel) |
| `BtTestApp.kt` | `headsetHub` на уровне Application |

---

## Жесты

| Жест | Результат |
|------|-----------|
| Одиночный Play / Pause / Hook | После окна `nextDoubleTapMs` (400 ms) → **Play** |
| Второй Play-жест в окне | **Next (2×Play)** |
| Аппаратный `MEDIA_NEXT` | **Next** + suppress companion Play |
| Debounce после commit | Опционально, 500 ms по умолчанию |

Настройки: `bttest_v1_headset_button_prefs`.

---

## Claim media-button session (v1.0.1)

На Android только одна «Media button session». Чтобы BT_TestV1 её получил:

1. `AudioFocus` (`AUDIOFOCUS_GAIN`, USAGE_MEDIA).
2. Краткий `STATE_PLAYING` → `STATE_PAUSED` на MediaSession.
3. `MediaPlaybackPulse` — тихий AudioTrack ~180 ms под UID приложения.
4. Кнопка / API **Reassert** пересоздаёт сессию и повторяет pulse.

При включении Native capture вызываются `start()` и сразу `reassert()`.

---

## Порт в другое приложение

1. Скопировать каталог `app/src/main/java/com/bttest/v1/headset/` (или переименовать пакет).
2. В `Application` создать:
   ```kotlin
   headsetButtonPreferences = HeadsetButtonPreferences(this)
   headsetHub = HeadsetButtonHub(headsetButtonPreferences)
   ```
3. В манифесте:
   - permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
     `POST_NOTIFICATIONS`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`;
   - service `HeadsetMonitorService` с `foregroundServiceType="mediaPlayback"`.
4. Зависимость: `androidx.media:media:1.7.0`.
5. Строки `bt_monitor_*` + иконка уведомления.
6. Подписаться на `headsetHub.events` / `headsetHub.state` или встроить `BluetoothTestScreen`.
7. Старт захвата: `HeadsetMonitorService.start(context)` + при необходимости `reassert(context)`.

Минимальный API для своей логики (без UI теста):

```kotlin
// Application
val hub = HeadsetButtonHub(HeadsetButtonPreferences(this))

// Слушать события
lifecycleScope.launch {
    hub.events.collect { event ->
        // event.label, event.kind (HARDWARE/SIMULATED), event.viaDoublePlay
    }
}

HeadsetMonitorService.start(this)
HeadsetMonitorService.reassert(this) // забрать session у конкурентов
```

В `HeadsetMonitorService` заменить cast на ваш `Application`, если имя класса другое.

---

## Манифест (эталон)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<service
    android:name=".headset.HeadsetMonitorService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

---

## Типичные сбои

| Симптом | Действие |
|---------|----------|
| Симуляция есть, HARDWARE нет | `dumpsys media_session`: кто владеет session? |
| Session = `com.nous.ahcc` | `adb shell am force-stop com.nous.ahcc`, затем Reassert |
| Session = YouTube / Spotify | Пауза / force-stop, затем Reassert |
| Next + Play оба | Нужен suppress после Next (уже в Hub) |
| Нет уведомления FGS | Выдать `POST_NOTIFICATIONS` |

Диагностика из logcat:

```bash
adb logcat -s BTTestV1:D BTTestV1-SVC:D BTTestV1-Pulse:D MediaSessionService:D
```

---

## Связь с AHCC

Логика жестов совпадает с экраном «Тест BT-кнопок» в `AndroidHermesCloudChat`.  
AHCC дополнительно содержит чат Hermes; BT_TestV1 — только BT-стенд для отладки и переноса.

**Важно:** одновременно AHCC и BT_TestV1 с включённым Native capture конфликтуют — кнопки получит только один media-button session (обычно тот, кто последний сделал claim / pulse).
