# Отчёт: медиа и голос AHCC ↔ Hermes Cloud

**Версия AHCC:** 1.2.0 (6)  
**Протокол:** [AHCC-Hermes-Media-Protocol.md](../Protocols/AHCC-Hermes-Media-Protocol.md) (`ahcc.media.v1`)

## Что сделано

| Функция | Как |
|---------|-----|
| Отправка файлов | SAF picker → `kind=file` в `<<<AHCC_MEDIA_V1>>>` |
| Фото из приложения | Camera + JPEG compress → `kind=photo` + OpenAI `image_url` (fallback text) |
| BT Play → голос | Подписка на Play-жест Hub: старт/стоп `MediaRecorder` AAC → `kind=voice` |
| Mic в UI | То же, кнопка в композере |
| Воспроизведение | Парсинг входящего `AHCC_MEDIA_V1` / `[AHCC_VOICE]` → `MediaPlayer` |

## Транспорт

- HTTP SSE (Nous Inference): envelope в `messages[].content`; фото дополнительно как multimodal.
- WebSocket: `user_message` + `attachments[]` + тот же текст envelope.

## UX

Подключите чат → MediaSession для BT поднимается автоматически.  
Play (одиночный жест) / Mic — запись; повтор — отправка.
