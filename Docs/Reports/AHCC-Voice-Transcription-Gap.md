# Отчёт: транскрипция голоса AHCC → Hermes Cloud

**Дата:** 2026-09-22  
**Версия AHCC:** 1.2.6 (12)  
**Связанные документы:**
- [AHCC-Hermes-Media-Protocol.md](../Protocols/AHCC-Hermes-Media-Protocol.md)
- [AHCC-Media-Voice.md](./AHCC-Media-Voice.md)
- [AHCC-Supabase-Sessions.md](../Guides/AHCC-Supabase-Sessions.md)

---

## 1. Симптом

В экране **Settings → «Тест голоса (Start / Play / Send)»**:

1. Запись AAC/M4A (Start/Stop) и локальное Play работают.
2. **Send to Hermes Cloud** уходит на Nous Inference (`POST …/v1/chat/completions`, модель `z-ai/glm-4.5-air`).
3. В окне «Ответ агента» появляется **длинный HTML** (порядка **17 000** символов), а не расшифровка речи.

Пример из logcat (тестовая отправка):

```text
AHCC-VoiceTest: send 172862B envelopeChars=231156 session=…
AHCC-HTTP: -> POST https://inference-api.nousresearch.com/v1/chat/completions
            model=z-ai/glm-4.5-air multimodal=false requestChars=231156
AHCC-HTTP: stream done chars=17640
```

То есть HTTP-ответ успешный, но содержимое — не транскрипт.

---

## 2. Что делает клиент сейчас

| Шаг | Поведение |
|-----|-----------|
| Запись | `MediaRecorder` → AAC в `.m4a` (`audio/mp4`) |
| Упаковка | Блок `<<<AHCC_MEDIA_V1>>>` с `kind=voice`, `encoding=base64`, полный `data` |
| Транспорт | **HTTP SSE / Nous Inference** (текстовый `messages[].content`) |
| Подпись | Просьба: «Transcribe… ONLY the transcription text» |
| Supabase | Для голоса на Hermes **не обязателен**; в историю можно писать только placeholder `[voice] …` |

Протокол `ahcc.media.v1` доставляет **байты аудио внутри текста**. Этого достаточно, чтобы *передать файл*, но недостаточно, чтобы *текстовая* LLM его «услышала».

---

## 3. Корневая причина

**Nous Inference + текстовая модель (`glm-4.5-air`) не выполняют speech-to-text по base64 в content.**

- Запрос — обычный chat completion: огромная строка (подпись + JSON + base64).
- Модель трактует вложение как текст/шум и генерирует произвольный длинный ответ (часто HTML).
- Это не сбой сети и не ошибка упаковки AHCC: `stream done` без HTTP error.

Отличие от **AndroidChat**: там BT Play → **SpeechRecognizer (STT на устройстве)** → в чат уходит уже **текст**, не аудиофайл.

---

## 4. Что нужно, чтобы Hermes реально транскрибировал

Нужен **отдельный STT-этап** (или модель/API с native audio). Варианты:

### A. STT на устройстве (рекомендуемый быстрый путь)

1. После Stop / тишины: Android `SpeechRecognizer` или локальный Whisper.
2. В Hermes / Inference отправлять **только текст** транскрипта.
3. Аудиофайл опционально хранить локально или в Storage — не для LLM.

Плюсы: просто, без смены бэкенда. Минусы: качество/язык зависят от on-device STT; сырой файл до агента не доходит.

### B. STT-сервис перед Inference

1. AHCC (или промежуточный сервис) вызывает Whisper API / Google STT / свой endpoint.
2. В `chat/completions` уходит текст.
3. При желании — параллельно класть файл в Supabase Storage для AHCCDV.

### C. Hermes Agent + tool `transcribe_audio`

1. Переключить голос с голого Inference на **Cloud Agent** с tools.
2. Tool принимает вложение / `AHCC_MEDIA_V1` / URL файла и вызывает Whisper (или аналог).
3. Агент возвращает транскрипт (и дальше отвечает по смыслу).

Плюсы: «настоящий» Hermes-путь. Минусы: нужна настройка агента, tools, доступ к STT API.

### D. Модель / API с native audio

Если у Nous Portal появится endpoint с `input_audio` / multimodal audio:

1. Слать аудио в формате API (не как base64 в текстовом envelope, либо дополнительно).
2. Обновить клиент (`HermesHttpSseClient`) под схему провайдера.

Пока такого пути в текущем AHCC HTTP SSE нет.

---

## 5. Роль Supabase

| Задача | Нужно менять схему? |
|--------|---------------------|
| Транскрипция на Inference/Hermes | **Нет** |
| История/AHCCDV: только факт «было голосовое» | Нет — placeholder в `ahcc_messages` |
| Общий доступ к **бинарному** аудио между AHCC / AHCCD / AHCCDV | **Да, позже** — Supabase Storage (или `bytea` / base64-колонка); текущий `ahcc_files.content` — `text` |

---

## 6. Уже сделано в AHCC (контекст отладки)

- Экран **Тест голоса**: Start / Stop / Play / Send, отображение ответа, snackbar «Аудиофайл отправлен».
- Автопрокрутка ответа; предупреждение, если ответ похож на HTML.
- В основном чате: Play → запись, тишина → автоотправка; Play игнорируется, пока ждём ответ.
- Исправления двойного Play/send, MediaSession (AudioFocus / pulse).

Эти изменения подтверждают: **доставка файла работает, отсутствует именно STT.**

---

## 7. Рекомендация

1. **Краткосрочно:** вариант **A** или **B** — транскрипт на клиенте/сервисе, в Hermes только текст.  
2. **Среднесрочно:** вариант **C** — Agent tool для аудио, если нужна серверная обработка файла.  
3. Не ожидать транскрипции от текущей связки **Inference + glm-4.5-air + AHCC_MEDIA_V1 base64**.

---

## 8. Критерии готовности

- [ ] После Send в «Ответ агента» — краткий текст распознанной речи (не HTML).  
- [ ] В логах: либо STT-результат до POST, либо вызов Agent tool `transcribe_*`, либо native-audio API.  
- [ ] Регрессия: Start/Stop/Play на экране теста голоса без поломки текстового чата.
