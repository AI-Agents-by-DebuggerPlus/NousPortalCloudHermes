# AHCC Desktop ↔ Cloud Hermes — передача файлов (Telegram)

**Дата:** 2026-10-19  
**Статус:** подтверждено пользователем — **двухсторонняя передача файлов работает**  
**Версия клиента:** AHCC Desktop **1.4.28 (34)**  
**Канал связи с агентом:** Telegram user session (TDLib / TDLight) → bot Cloud Hermes (например `@npch_operator_bot`)

**Supabase** в живом пути Desktop ↔ Cloud Hermes **не участвует** (опционально: история сессии / `ahcc_files` для peer Android). Транспорт файлов к агенту и обратно — **Telegram**.

---

## Проблема (исходная)

Двусторонняя передача по Telegram **ломалась** на разных этапах.

### 1. Desktop → агент (до 1.4.26)

1. UI собирал файл (`sendPendingFile`), строил envelope `AHCC_MEDIA_V1` и placeholder `[photo]…` / `[file]…`.
2. `DesktopChatGateway.sendMedia` для **Telegram** отправлял **только placeholder-текст** — байты файла отбрасывались.
3. Envelope с base64 уходил только в **HTTP SSE**. Лимит длины сообщения Telegram (~4096) не подходит для крупных base64 — нужен нативный `photo`/`document`.
4. Текстовые файлы: upload в Supabase + notify `Shared file via Supabase: …` **без** документа в Telegram → агент видел имя, не содержимое.

### 2. Агент → Desktop (до 1.4.27)

- Входящие `MessagePhoto` / `MessageDocument` обрабатывались слабо: часто только caption; файл не сохранялся локально и не открывался из чата.
- Пользователь видел файл в Telegram Desktop / у агента, но не в AHCC Desktop.

### 3. Ложные «входящие» (до 1.4.28)

- TDLib видит **весь** аккаунт пользователя. Автосохранение медиа шло **из любых чатов/каналов**, не только watched AHCC-ботов → в `Incoming` попадали чужие графики (например торговые), которых не было в чате Operator.

---

## Решение

### Исходящие (1.4.26) — `sendLocalFileAndWaitReply`

1. `DesktopTelegramUserSession.sendLocalFileAndWaitReply`:
   - изображения → `InputMessagePhoto` / `InputPhoto` + `InputFileLocal`;
   - остальное → `InputMessageDocument` / `InputDocument` + `InputFileLocal`;
   - caption ≤ 1024; далее тот же wait-for-reply, что у текста.
2. `DesktopChatGateway.sendMedia(localPath, asPhoto)`:
   - **Telegram** → TDLib photo/document;
   - **HTTP SSE** → `AHCC_MEDIA_V1` envelope.
3. Текстовый путь: после Supabase (если настроен) всё равно шлётся **локальный файл** документом боту.

### Входящие (1.4.27)

1. Настройка **Settings → Connection → Save received files to** (`DesktopUiSettings.inboundFilesDir`, по умолчанию `~/AHCC-Downloads`).
2. Download через TDLib `DownloadFile` → копия в выбранную папку.
3. В пузырях исходящих и входящих: **📎 имя — Open** (`localAttachmentPath` / `attachmentName` на `ChatMessage`).

### Только watched-боты (1.4.28)

1. `onIncoming` / `downloadAndSaveInbound` — **только** `isWatchedChat` (addressee-боты).
2. В лог при сохранении: `bot=@… chatId=… msgId=… kind=…` — чтобы сверять источник.
3. В `telegram-raw` логируются и медиа без caption.

---

## Проверка (факт)

| Направление | Результат |
|-------------|-----------|
| Desktop → Cloud Hermes (текст/файл, в т.ч. тест код + сумма + возврат файла) | OK |
| Cloud Hermes → Desktop (документ/фото в чат + сохранение + Open) | OK |
| Чужие каналы/чаты аккаунта не должны писать в inbound (после 1.4.28) | OK (by design) |

---

## Версии линии

| Версия | Что |
|--------|-----|
| 1.4.24 | Кликабельные ссылки в пузырях |
| 1.4.25 | Shift+Enter — newline в позиции каретки |
| 1.4.26 | Исходящие файлы через TDLib photo/document |
| 1.4.27 | Папка inbound + Open в чате |
| **1.4.28** | Inbound только от watched-ботов + лог источника |

---

## Ограничения

- Лимит бинарного пути Desktop: **~2 MiB** (`DesktopMediaEnvelope.MAX_FILE_BYTES`) для envelope/HTTP; Telegram document/photo — лимиты TDLib/Telegram.
- Читать произвольные каналы/группы, на которые подписан пользователь, AHCC Desktop **умеет на уровне TDLib**, но **намеренно не делает** этого в UI/inbound (только watched-боты). Белый список каналов — отдельная фича, не реализована.
- Inline-кнопки approve (Allow Once / Session / Deny) — отдельная тема markup UI.
