# AHCC: русские резюме memory-review

Hermes по умолчанию шлёт **verbose**-уведомления о staged-изменениях памяти **на английском** (фоновый self-improvement / `/memory pending`). MainAgent их **не переписывает** — это не обычный chat-turn.

## Что делает AHCC (1.4.55+)

- **Deny all** → **`/memory reject all`** (не по одному id).
- Парсер id игнорирует слово **Pending** из заголовка «Pending memory writes» — только hex-id (8 символов).

## Ранее (1.4.54+)

- В **чате** и в **overlay «Память Hermes»** показывается **краткое резюме на русском** (`MemoryReviewSummarizer`).
- Полный английский текст остаётся в overlay блоком «Подробно (как от Hermes)».
- Ответы на `/memory approve` / `reject` с ошибкой *Entry changed since it was staged* тоже сжимаются до понятной русской строки.

## Если нужен русский текст уже в Telegram

Нужна доработка **сервера Hermes** (шаблон memory notifications) или отдельный post-processing hook — в этом репозитории исходников Hermes нет. До появления hook AHCC-local summary — основной UX.
