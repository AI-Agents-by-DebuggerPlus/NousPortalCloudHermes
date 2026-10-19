# AHCC Desktop ↔ Cloud Hermes — передача файлов (Telegram)

**Дата:** 2026-10-19  
**Версия клиента:** AHCC Desktop **1.4.26 (32)**  
**Канал:** Telegram user session (TDLib / TDLight) → bot Cloud Hermes (например `@npch_operator_bot`)

---

## Проблема

Двусторонняя передача файлов между **AHCC Desktop** и **Cloud Hermes** по Telegram **не работала** на исходящем пути Desktop → агент.

### Что происходило

1. UI Desktop собирал файл (`sendPendingFile`), строил envelope `AHCC_MEDIA_V1` (base64) и короткий placeholder вида `[photo] name.png` / `[file] …`.
2. В `DesktopChatGateway.sendMedia` для канала **Telegram** в бота уходил **только placeholder-текст**:

```kotlin
// было (сломано)
sendMessage(historyPlaceholder)  // без байтов файла
```

3. Полный envelope с данными уходил только в канал **HTTP SSE**. Лимит Telegram на длину сообщения (~4096) всё равно не позволил бы слать 2 MiB base64 текстом — нужен был нативный `photo`/`document`.
4. Для текстовых файлов (`.md` / `.txt` / …) файл клался в Supabase (`ahcc_files`), а боту уходило лишь уведомление `Shared file via Supabase: …` **без** самого документа.

Итог: агент на Cloud видел подпись/маркер, но **не получал файл**; обратный путь (agent → Desktop) мог уже работать, исходящий — нет.

---

## Решение

### Telegram: реальная отправка через TDLib

1. В `DesktopTelegramUserSession` добавлен `sendLocalFileAndWaitReply`:
   - изображения → `InputMessagePhoto` / `InputPhoto` + `InputFileLocal`;
   - остальное → `InputMessageDocument` / `InputDocument` + `InputFileLocal`;
   - caption = placeholder / notify (до 1024 символов);
   - далее тот же wait-for-reply, что и у текстовых сообщений.

2. `DesktopChatGateway.sendMedia` принимает `localPath` и `asPhoto`:
   - **Telegram** → TDLib photo/document по пути на диске;
   - **HTTP SSE** → по-прежнему `AHCC_MEDIA_V1` envelope в теле запроса.

3. `Main.sendPendingFile`:
   - бинарный путь передаёт `file.absolutePath` в `sendMedia`;
   - текстовый путь после upload в Supabase тоже шлёт **локальный файл** документом боту (не notify-only).

### Проверка

После 1.4.26 подтверждено: **двухсторонняя** передача файлов AHCC Desktop ⇔ Cloud Hermes по Telegram работает.

---

## Связанные правки той же линии Desktop (контекст)

| Тема | Версия / место |
|------|----------------|
| Кликабельные `https://` и markdown-ссылки в пузырях | 1.4.24, `MessageContentBlocks.kt` |
| Shift+Enter: `\n` в позиции каретки | 1.4.25, `TextFieldValue` в `Main.kt` |
| Отправка файлов Telegram | **1.4.26**, этот отчёт |

---

## Ограничения

- Клиентский лимит бинарного пути Desktop: **~2 MiB** (`DesktopMediaEnvelope.MAX_FILE_BYTES`).
- Агент должен уметь принимать Telegram photo/document (типичное поведение Cloud Hermes / Operator).
- Inline-кнопки approve (Allow Once / Session / Deny) — отдельная тема UI markup; к передаче файлов не относится.
