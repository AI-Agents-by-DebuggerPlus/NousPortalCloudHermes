# Отчёт: переход AHCC на Hermes Agent по WebSocket

**Версия:** 1.3.0 (13)  
**Дата:** 2026-09-22  
**Протокол:** [AHCC-Hermes-Media-Protocol.md](../Protocols/AHCC-Hermes-Media-Protocol.md) §4 (схема остаётся `ahcc.media.v1`)

Исторические отчёты о баге Inference не изменялись.

---

## Что изменено

| Файл | Суть |
|------|------|
| `domain/model/HermesModels.kt` | Дефолт транспорта — `WebSocket`. `fromStorage`: неизвестное/пустое значение → WebSocket; `http_sse` по-прежнему парсится, но UI и gateway его не выбирают. Поле `model` помечено как legacy HTTP SSE. |
| `data/local/ConnectionPreferences.kt` | При загрузке prefs транспорт всегда `WebSocket` (старый `http_sse` в DataStore игнорируется). |
| `data/HermesChatGateway.kt` | `connect()` всегда открывает WebSocket. `sendMedia` на WS кладёт в `content` короткую подпись (`historyPlaceholder`), байты — в `attachments`. |
| `data/websocket/HermesWebSocketManager.kt` | Кадр без `model`. Лог: длина текста, kind/size вложений, без дампа base64. Чтение — только `for (frame in incoming)`. |
| `presentation/settings/SettingsScreen.kt` | Убран переключатель HTTP SSE и поле модели. Остались host/port/TLS/ключ/сессия. |
| `presentation/chat/ChatScreen.kt` | Шапка: `WS · host:port`, без имени модели. |
| `presentation/chat/HermesChatViewModel.kt` | Медиа уходит wires + короткая подпись. Полный текстовый envelope с base64 для WS не собирается. |
| `presentation/voice/VoiceTestScreen.kt` | Send через `chatGateway` (WS). Подпись «Голосовое сообщение (N ms)». Эвристика «ответ = HTML» убрана; текст агента показывается как есть. Автопрокрутка сохранена. |
| `config-templates/HermesConfig.android.kt.example` | `TRANSPORT = websocket`. `MODEL` — комментарий «только legacy HTTP». |
| `Docs/Protocols/AHCC-Hermes-Media-Protocol.md` | §4: `attachments[]` — основной канал бинарных данных на WS; `content` — подпись. Версия схемы не повышалась. |

`HermesHttpSseClient.kt` **не удалён** (см. ниже). `HeadsetButtonHub` / жест записи не менялись в этой задаче.

---

## Итоговый формат WS-кадра для голоса

Уходит `HermesWebSocketManager.sendMediaMessage` → JSON `HermesRequest` (поля `model` нет):

```json
{
  "event": "user_message",
  "session_id": "<session>",
  "content": "Голосовое сообщение (4100 ms)",
  "data": { "role": "user", "content": "Голосовое сообщение (4100 ms)" },
  "attachments": [
    {
      "id": "<uuid>",
      "kind": "voice",
      "mime": "audio/mp4",
      "name": "voice_….m4a",
      "size": 48210,
      "duration_ms": 4100,
      "encoding": "base64",
      "data": "<base64 AAC>",
      "sha256": "<hex>"
    }
  ]
}
```

Фото и файл — тот же каркас, другой `kind` / `mime`. Base64 только в `attachments[].data`.

---

## Судьба HTTP SSE-клиента

**Оставлен в коде как legacy, в пользовательском UI недоступен.**

Причина: класс уже шлёт `model` и base64 внутри текста Inference; резкое удаление затронет desktop-модуль отдельно и историю протокола §3. Android gateway больше не вызывает `httpSse.connect`. Единственный рабочий путь приложения — WebSocket.

---

## Судьба поля `model` на клиенте

- В WS-кадр **не сериализуется** (`HermesRequest` его не содержит).
- Остаётся в `ConnectionConfig.model` и `HermesConfig.MODEL` только потому, что им пользуется неиспользуемый `HermesHttpSseClient` (`POST /v1/chat/completions`).
- Экран настроек поле модели не показывает и при сохранении не перезаписывает его осмысленным выбором пользователя (пишется прежнее значение из state).

Модель агента задаётся на сервере.

---

## Подтверждение отсутствия поллинга

Проверено поиском по `app/src/main/java` (`Timer`, циклы `GET`, `HermesSessionHistoryApi`):

- Связь с агентом: `HermesWebSocketManager.openSession` — `for (frame in this.incoming)` (push). Периодических HTTP-запросов к Hermes/Inference из этого цикла нет.
- `delay` в `connectWithRetry` — пауза **перед повторным connect после обрыва**, не опрос состояния. Сохранён как был.
- `HermesSessionHistoryApi` в Android-модуле **нигде не вызывается**.
- Загрузка истории при Connect идёт в **Supabase** (`AhccSupabaseClient.fetchSessionMessages`) один раз, не таймером. Это не канал Hermes Agent.
- `delay(100)` в `watchSilenceAndAutoSend` — порог тишины микрофона, не сеть.

Нового поллинга Hermes не добавлено.

---

## Допущения о серверной стороне

Проверено на живом хосте только handshake (см. ниже): **403**, сессия кадров не открывается. Остальное — предположения:

- Если handshake пройдёт, `/v1/ws/chat` примет JSON `user_message` с `attachments[]` (`kind`, `mime`, `encoding=base64`, `data`).
- Неизвестное агенту поле он игнорирует либо ответит `error` в кадре.
- Транскрипция на клиенте не делается. Без tool вроде `transcribe_audio` ответ может не быть расшифровкой.
- Обратный голос агента по-прежнему ожидается текстом кадра с `AHCC_MEDIA_V1` / `[AHCC_VOICE]`.

Факт, не предположение: текущий Bearer `sk-nous` на `cloudhermesagent-4280` для WS **не принимается** (403 вместо 101).

---

## Как тестировалось

- Сборка и установка **1.3.0 (13)** на Pixel 6a. Connect из приложения.
- Logcat `AHCC-WS` (2026-09-22 ~19:06–19:07), pid приложения `versionName=1.3.0`:

```text
connect requested wss://cloudhermesagent-4280.agents.nousresearch.com:443/v1/ws/chat?session_id=desktop_test_session keyLen=40
connect failed: WebSocketException: Handshake exception, expected status code 101 but was 403
```

Повторы с backoff 1 с → 30 с. Кадр `user_message` / голосовое **не отправлялись**: рукопожатие не доходит до 101.

- Живой агент с `transcribe_audio` не проверялся: сокет не открывается.
- Контракт тела кадра (короткий `content` + `attachments[]`) сверен по коду `sendMediaMessage`, не по проводу.

---

## Что уточнить и доделать дальше

Клиентская часть соответствует замыслу: только WebSocket, только подписка на кадры, модель выбирает агент. Следующий шаг **не в Android-коде**. Пока handshake не проходит, голос физически не уходит дальше сокета.

1. **Авторизация `/v1/ws/chat`.** Логи 1.3.0: `Authorization: Bearer` с ключом Inference (`sk-nous`, `keyLen=40`) даёт **403**, не 101. Нужен другой механизм — по аналогии с `API_SERVER_KEY` для истории сессий. Уточнить у Nous Portal / документации Hermes Agent, чем аутентифицируется этот сокет: другой bearer, обмен ticket, cookie-сессия. В старом гайде есть `ws-ticket`, но для именно `/v1/ws/chat` на cloud это **не подтверждено**, только гипотеза. Без рабочих учётных данных кадр `user_message` отправить нельзя.

2. **`HermesHttpSseClient` не удалён.** Оставить его неиспользуемым фолбэком разумно: desktop-модуль отдельно зависит от Inference. Отдельный тикет на удаление — когда desktop тоже уйдёт с Inference, иначе класс будет молча устаревать.

3. **STT на сервере не проверялся.** До открытого сокета дело не дошло. Tool `transcribe_audio` на стороне Hermes Agent — отдельная, ещё не начатая работа. В репозитории AHCC это не реализуется.

4. **Сквозной сценарий не прогонялся.** Проверены код и один прогон логов до 403. Цепочки Start → Stop → Send с успешным транскриптом ещё не было.

Дополнительный риск после успешного connect: большой base64 в одном текстовом WS-кадре может упереться в лимит размера кадра.

---

## Соответствие критериям приёмки (`AHCC-Voice-Transcription-Gap.md` §8)

| Пункт | Статус |
|-------|--------|
| После Send — текст, не HTML от текстовой LLM | **Открыто.** Клиент больше не ходит в Inference, но WS handshake **403** — кадр с голосом не уходит. Транскрипт ещё и зависит от STT на агенте. |
| В логах виден путь доставки (не base64 в HTTP body) | **Частично.** В логе есть целевой `wss://…/v1/ws/chat` и 403. Строки `attachments=[voice:…]` не было: отправки не случилось. |
| Start/Stop/Play теста голоса | Запись/Play в этой проверке логов не фигурировали. Send заблокирован отсутствием сокета. |
