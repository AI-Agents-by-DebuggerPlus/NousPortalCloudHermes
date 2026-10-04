# AHCC — тестирование одиночного / двойного / тройного Play (экран «Тест BT-кнопок»)

Версия приложения на момент отчёта: **1.4.62 (81)**  
Маршрут: `bluetooth_test` → `BluetoothTestScreen`  
Ядро логики: `HeadsetButtonHub` + `HeadsetButtonPreferences`  
Захват железа: `HeadsetMonitorService` (FGS + `MediaSessionCompat`)

См. также общий отчёт по захвату кнопок: [BT-Headset-Test.md](BT-Headset-Test.md).

---

## Цель экрана

Проверить, что AHCC различает **три жеста** на media-кнопке гарнитуры и показывает их отдельными счётчиками, **без** старта голосовой записи в чате.

| Счётчик UI | Смысл |
|------------|--------|
| **Play** | одиночный жест Play/Pause |
| **Next** | двойной жест (или аппаратный Next) |
| **Stop** | тройной жест (или аппаратный Previous/Stop) |

---

## Как открыть и подготовить

1. AHCC → иконка гарнитуры в шапке чата (или Настройки) → **«Тест BT-кнопок»**.
2. Включить **Native capture** (при необходимости разрешить уведомления / Bluetooth).
3. Поставить на паузу Spotify / YouTube / другие приложения с активной MediaSession — иначе кнопки уйдут им.
4. На экране теста запись голоса **отключена** (`btTestActive` + `voiceGesturesEnabled = false`).

При уходе с маршрута `bluetooth_test` флаги сбрасываются; чат снова может обрабатывать Play для голоса.

---

## Два канала одного «нажатия»

Buds и Android часто отдают жест **двумя способами**:

1. **Аппаратный жест прошивки** (предпочтительно для double/triple на Galaxy Buds и аналогах):
   - single → `MEDIA_PLAY` / `MEDIA_PAUSE`
   - double → `MEDIA_NEXT`
   - triple → `MEDIA_PREVIOUS`
2. **Серия Play/Pause** (когда каждое физическое нажатие — toggle PLAY↔PAUSE):
   - 1× → Play  
   - 2× в окне серии → Next  
   - 3× в окне серии → Stop  

Оба канала сходятся в одни и те же счётчики на экране теста.

```
Гарнитура
   │
   ├─ MEDIA_NEXT ──────────────────────────► Next
   ├─ MEDIA_PREVIOUS / MEDIA_STOP ─────────► Stop
   │
   └─ MEDIA_PLAY / MEDIA_PAUSE / Hook
            │
            ▼
     HeadsetButtonHub (серия в окне)
            │
            ├─ settle 1× ──► Play
            ├─ settle 2× ──► Next (2×Play)
            └─ сразу / settle 3× ──► Stop (3×Play)
```

---

## Алгоритм серии Play/Pause (`HeadsetButtonHub`)

Пока открыт не Play-tap-тест (`playTestActive == false` — режим BT-кнопок и чата):

### Окно серии

- База: настройка **«Интервал Next/Stop / серии Play (мс)»** (`nextDoubleTapMs`).
- Фактически не меньше **900 ms** (`MIN_BT_SERIES_MS`), чтобы типичная цепочка PLAY → PAUSE → PLAY (~400–600 ms между событиями) успевала набрать 3×.

### Echo (не считать два колбэка одним тапом)

Один физический клик часто приходит дважды (`mediaButtonEvent` + `onPlay`, или PLAY+PAUSE подряд).

| Ситуация | Решение |
|----------|---------|
| PLAY↔PAUSE с Δ &lt; 180 ms | echo (тот же клик) |
| PLAY↔PAUSE с большим Δ | **новый** тап (toggle следующего нажатия) |
| Тот же label, другой `source`, Δ &lt; 220 ms | echo |
| Тот же source, снова PLAY | новый тап |

### Фиксация жеста

1. Каждый не-echo Play-жест увеличивает `burstCount` и перезапускает таймер settle.
2. Если `burstCount` достигает **3** — серия фиксируется **сразу** → **Stop (3×Play)**.
3. Иначе после паузы settle:
   - 1 → **Play**
   - 2 → **Next (2×Play)**
   - ≥3 → **Stop (3×Play)**
4. После Next/Stop companion Play подавляется на длительность окна серии (`suppressPlayUntilMs`).
5. После одиночного Play **нет** длинного lockout (раньше lockout «съедал» 2-е и 3-е нажатия тройного жеста).

### Режим Play-tap-теста (другой экран)

На экране «проверка кратности Play» (`playTestActive == true`) та же серия уходит в счётчики **1× / 2× / 3× Play**, а не в Next/Stop. К BT-окну это не относится.

---

## Аппаратные Next / Stop (жесты Buds)

Обработка в `notifyButton` **до** логики серии:

| Событие | Счётчик | Подпись в журнале |
|---------|---------|-------------------|
| `MEDIA_NEXT` | Next | `Next` |
| `MEDIA_PREVIOUS` | Stop | `Stop (Prev)` |
| `MEDIA_STOP` | Stop | `Stop` |

Именно поэтому «тройное нажатие» на Buds стабильно даёт **Stop**: прошивка шлёт **Previous**, а не три отдельных Play. Двойное нажатие аналогично шлёт **Next**.

После аппаратного Next/Stop pending-серия Play сбрасывается, краткий suppress не даёт ложного Play от companion-события.

---

## UI экрана «Тест BT-кнопок»

Файл: `presentation/bluetooth/BluetoothTestScreen.kt`

- Три крупных счётчика: **Play** / **Next** / **Stop**.
- Переключатель Native capture, защита от повторного нажатия (debounce prefs), поле интервала серии.
- Журнал: до 40 строк `[HARDWARE]` / `[SIMULATED]`.
- Симуляция:
  - несколько Play подряд (с паузой меньше окна) → Next / Stop по правилам серии;
  - кнопка **Next** → Next;
  - **Previous** / **Stop** → Stop.

Состояние: `HeadsetTestState.pressCount`, `nextCount`, `stopCount`, `eventLog`, …

---

## Изоляция от голосовой записи

Пока активен маршрут теста BT:

1. `AhccNavHost` → `HermesChatViewModel.enterBluetoothTest()`:
   - `headsetHub.btTestActive = true`
   - `voiceGesturesEnabled = false`
   - отмена текущей записи / STT
2. `BluetoothTestScreen` DisposableEffect дублирует флаги на всякий случай.
3. `claimHeadsetButtons()` не включает голос, пока `btTestActive` или `playTestActive`.
4. `onHeadsetEvent` / `onVoicePlayPressed` игнорируют Play на экранах теста (`suppressChatVoice`).

Итог: жесты на экране теста только крутят счётчики и журнал.

---

## Настройки (SharedPreferences)

Имя файла prefs: `ahcc_headset_button_prefs`.

| Ключ / поле | Назначение | По умолчанию |
|-------------|------------|--------------|
| `next_double_tap_ms` | Интервал серии / suppress после Next·Stop | 650 ms (на практике окно ≥ 900 ms) |
| `debounce_enabled` / `debounce_interval_ms` | UI-переключатель защиты (prefs) | 500 ms |
| multiplicity_* / play_lockout_* | Экран Play-tap-теста | 2 s / 5 s / 5 s |

---

## Проверка вручную

1. Capture ON, медиа-плееры на паузе.
2. **1×** нажатие на Buds → счётчик Play +1, голос не стартует.
3. **Double**-жест Buds → Next +1 (в журнале часто просто `Next`, не `2×Play`).
4. **Triple**-жест Buds → Stop +1 (часто `Stop (Prev)`).
5. Симуляция: Previous → Stop; три быстрых Play на симуляторе → Stop (3×Play).
6. «Сбросить счётчики» обнуляет Play/Next/Stop и журнал.
7. Выйти с экрана → в чате одиночный Play снова может начать запись.

Логи Logcat: тег **`AHCC-BT`** (`settle →`, `triple now →`, `BT Next`, `BT Stop`).

---

## Ключевые файлы

| Файл | Роль |
|------|------|
| `headset/HeadsetButtonHub.kt` | серия 1/2/3, Next/Stop, echo, `btTestActive` |
| `headset/HeadsetButtonPreferences.kt` | интервалы |
| `headset/HeadsetMonitorService.kt` | MediaSession → `notifyButton` |
| `headset/HeadsetButtonNames.kt` | нормализация меток |
| `presentation/bluetooth/BluetoothTestScreen.kt` | UI счётчиков |
| `presentation/navigation/AhccNavHost.kt` | enter/leave BT-теста |
| `presentation/chat/HermesChatViewModel.kt` | запрет голоса на тесте |

---

## Краткая шпаргалка

| Действие пользователя | Типичный вход | Счётчик |
|----------------------|---------------|---------|
| Одно нажатие | `MEDIA_PLAY` или короткий PLAY+PAUSE | **Play** |
| Двойной жест Buds | `MEDIA_NEXT` | **Next** |
| Тройной жест Buds | `MEDIA_PREVIOUS` | **Stop** |
| 2× Play/Pause в окне | серия burst=2 | **Next (2×Play)** |
| 3× Play/Pause в окне | серия burst=3 | **Stop (3×Play)** |
| Симуляция Previous/Stop | UI | **Stop** |
