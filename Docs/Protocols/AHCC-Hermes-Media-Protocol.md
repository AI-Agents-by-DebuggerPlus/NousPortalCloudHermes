# AHCC ↔ Hermes Cloud — протокол медиа и голосовых сообщений

**Версия схемы:** `ahcc.media.v1`  
**Приложение:** AndroidHermesCloudChat (AHCC)  
**Сервер:** Hermes Agent / Cloud на Nous Portal (`inference-api.nousresearch.com` + облачный агент)

---

## 1. Цели

| Направление | Что передаём |
|-------------|--------------|
| AHCC → Hermes | Текст, **фото**, **файлы**, **голосовые заметки** (audio) |
| Hermes → AHCC | Текст, опционально **голосовой ответ** (audio attachment) |
| UX AHCC | Камера / выбор файла; **BT Play** = старт/стоп записи голоса и отправка |

Протокол рассчитан на два транспорта:

1. **HTTP SSE** (Nous Inference, OpenAI-compatible) — основной путь AHCC сегодня.
2. **WebSocket** `/v1/ws/chat` — локальный / будущий cloud agent bridge.

---

## 2. Обёртка `AHCC_MEDIA_V1`

Бинарные вложения не уходят «сырым» WebSocket-бинарём: они кодируются в **текстовый блок**, который можно положить в `content` user/assistant сообщения.

### Маркеры

```text
<<<AHCC_MEDIA_V1
{JSON}
>>>
```

- Между маркерами — один JSON-объект UTF-8.
- Допускается произвольный текст **до** и **после** блока (подпись / ответ модели).
- Парсер на клиенте ищет первый блок `<<<AHCC_MEDIA_V1` … `>>>`.

### JSON-схема (корневой объект)

| Поле | Тип | Обязательно | Описание |
|------|-----|-------------|----------|
| `schema` | string | да | Всегда `"ahcc.media.v1"` |
| `direction` | string | да | `"client_to_agent"` или `"agent_to_client"` |
| `session_id` | string | нет | Идентификатор сессии AHCC |
| `caption` | string | нет | Подпись пользователя / пояснение |
| `attachments` | array | да | Список вложений (1…N) |

### Элемент `attachments[]`

| Поле | Тип | Обязательно | Описание |
|------|-----|-------------|----------|
| `id` | string (UUID) | да | Стабильный id вложения |
| `kind` | string | да | `voice` \| `photo` \| `file` |
| `mime` | string | да | Например `audio/mp4`, `image/jpeg`, `application/pdf` |
| `name` | string | да | Имя файла для UI / сохранения |
| `size` | number | да | Размер **декодированных** байт |
| `duration_ms` | number | нет | Длительность для `voice` |
| `encoding` | string | да | Сейчас только `"base64"` (стандартный Base64, без data-URI префикса) |
| `data` | string | да | Base64 полезной нагрузки |
| `sha256` | string | нет | Hex SHA-256 декодированных байт (целостность) |

### Пример: исходящее голосовое

```text
Голосовое сообщение

<<<AHCC_MEDIA_V1
{
  "schema": "ahcc.media.v1",
  "direction": "client_to_agent",
  "session_id": "android_test_session",
  "caption": "Голосовое сообщение",
  "attachments": [
    {
      "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "kind": "voice",
      "mime": "audio/mp4",
      "name": "voice_20260920_180501.m4a",
      "size": 48210,
      "duration_ms": 4100,
      "encoding": "base64",
      "data": "AAAA...."
    }
  ]
}
>>>
```

### Пример: входящий голос от агента

Агент (или tool TTS) может вернуть тот же блок с `direction: "agent_to_client"` и `kind: "voice"`.  
AHCC сохраняет файл в cache и воспроизводит через `MediaPlayer`.

Альтернативный короткий маркер (совместим с парсером AHCC):

```text
[AHCC_VOICE mime="audio/mpeg" name="reply.mp3"]
BASE64...
[/AHCC_VOICE]
```

---

## 3. Транспорт HTTP SSE (Nous Inference)

`POST {inferenceBaseUrl}/v1/chat/completions`

### 3.1 Текст / файл / голос

В историю и в `messages[].content` уходит **строка**: подпись + блок `<<<AHCC_MEDIA_V1 … >>>`.

Модели без native-audio всё равно видят:

- подпись (`caption`);
- метаданные (`kind`, `mime`, `name`, `duration_ms`);
- при необходимости — полный base64 (агент/инструменты на стороне Hermes Cloud могут извлечь).

Лимиты AHCC (клиент):

| kind | Рекомендуемый лимит сырых байт |
|------|--------------------------------|
| voice | ≤ 1.5 MiB (~2–3 мин AAC) |
| photo | ≤ 1.0 MiB после JPEG-сжатия |
| file  | ≤ 2.0 MiB |

При превышении клиент отклоняет отправку с ошибкой UI.

### 3.2 Фото: дополнительный multimodal path

Для `kind=photo` AHCC **дополнительно** отправляет OpenAI-совместимый content-array (если транспорт HTTP):

```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "<подпись + AHCC_MEDIA_V1 без data или с data>" },
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/jpeg;base64,<...>"
      }
    }
  ]
}
```

Если бэкенд/модель отклоняет multimodal (400), клиент повторяет запрос **только текстовым** envelope (с base64 внутри блока).

В истории HTTP после успешной отправки фото хранится текстовое представление (без повторной передачи огромного image_url в каждом следующем turn — в history кладётся короткий placeholder + имя файла).

---

## 4. Транспорт WebSocket

Основной транспорт AHCC → Hermes Agent. Бинарные данные идут в корневом `attachments[]` кадра `user_message` (схема элемента — как в §2, `ahcc.media.v1`, без смены версии: поле уже было в контракте). Текстовый блок `<<<AHCC_MEDIA_V1>>>` в `content` **не дублирует** base64.

```json
{
  "event": "user_message",
  "session_id": "android_test_session",
  "content": "Голосовое сообщение (4100 ms)",
  "data": {
    "role": "user",
    "content": "Голосовое сообщение (4100 ms)"
  },
  "attachments": [
    {
      "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "kind": "voice",
      "mime": "audio/mp4",
      "name": "voice_20260920_180501.m4a",
      "size": 48210,
      "duration_ms": 4100,
      "encoding": "base64",
      "data": "AAAA...."
    }
  ]
}
```

Поля `model` в кадре нет: модель выбирает Hermes Agent на сервере.

`attachments[]` — основной канал для `voice` / `photo` / `file`. `content` — короткая подпись. Текстовый envelope `AHCC_MEDIA_V1` остаётся fallback для legacy HTTP SSE (класс клиента сохранён, в UI не выбирается).

Входящие кадры: `token` / `message` / `done`. Голосовой ответ агента (`kind=voice`, `direction=agent_to_client`) клиент по-прежнему достаёт из текста кадра парсером `AHCC_MEDIA_V1` / `[AHCC_VOICE]`.

Транскрипция выполняется на стороне агента (например tool `transcribe_audio` по `attachments[]`). Клиент её не делает.

---

## 5. Семантика `kind`

| kind | Источник в AHCC | MIME по умолчанию | Поведение Hermes Cloud |
|------|-----------------|-------------------|-------------------------|
| `voice` | BT Play / кнопка mic, `MediaRecorder` AAC/M4A | `audio/mp4` | STT tool / native audio / сохранить |
| `photo` | Camera `TakePicture` + JPEG compress | `image/jpeg` | Vision / описание |
| `file`  | SAF document picker | по типу файла | Сохранить / разобрать tool'ами |

---

## 6. UX: BT Play → голос

1. При подключении чата AHCC поднимает `HeadsetMonitorService` (media-button session).
2. **Первый Play** (жест Play/Pause/Hook) → старт `MediaRecorder`, UI «Recording…».
3. **Второй Play** → стоп, сборка attachment `kind=voice`, отправка по протоколу, очистка temp-файла после успеха.
4. Double-tap → Next **не** должен стартовать запись: используется та же логика Hub (Next подавляет companion Play). Запись реагирует только на **закоммиченный Play** (одиночный жест после окна double-tap) — либо на явный UI mic.

Рекомендация реализации: подписка на `HeadsetButtonHub.events` / счётчик Play в chat ViewModel; Next игнорируется для записи.

---

## 7. Воспроизведение входящего голоса

1. После `done` / полного assistant `content` парсер ищет `AHCC_MEDIA_V1` или `[AHCC_VOICE]`.
2. Base64 → файл в `cacheDir/ahcc_media/`.
3. В UI — карточка «▶ Голосовое» у пузыря; автоплей — опционально (по умолчанию **по нажатию**).
4. Одновременно может играть только один трек (`VoicePlayer`).

---

## 8. Ошибки

| Код / ситуация | Поведение клиента |
|----------------|-------------------|
| Слишком большой файл | Не отправлять; snackbar |
| Нет RECORD_AUDIO / CAMERA | Запрос runtime permission |
| 400 multimodal | Retry text-only envelope |
| Пустая запись (< 400 ms) | Отменить, не слать |
| Конфликт MediaSession (AHCC vs Spotify) | Reassert / pause конкурента |

---

## 9. Совместимость с AndroidChat

AndroidChat передаёт файлы через Supabase (`type:"file"`).  
AHCC / Hermes Cloud **не** используют Supabase: аналог — `ahcc.media.v1` внутри чата Inference/WS.

Смысловой параллелизм:

| AndroidChat | AHCC |
|-------------|------|
| Storage + JSON pointer | Inline base64 в `AHCC_MEDIA_V1` |
| `kind: photo` | `kind: photo` + optional image_url |
| BT Play → STT text | BT Play → **audio file** voice note |
| TTS `[Voice]{en,ru}` | Входящий `kind: voice` audio playback |

---

## 10. Версионирование

- Несовместимые изменения → `ahcc.media.v2` и новые маркеры.
- Клиент AHCC должен игнорировать неизвестные `kind` / поля (forward-compatible).
