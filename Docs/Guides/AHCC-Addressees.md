# AHCC — адресаты сообщений

## Список получателей

- **В чате:** dropdown «Адресат», кнопки **Delete current receiver** и **Add new receiver**.
- Список сохраняется в DataStore на телефоне (переживает перезапуск).
- **MainAgent** (`liaison`) — по умолчанию, удалить нельзя, строка `TO:` не добавляется.

## Ключ адресации

Для любого получателя, кроме MainAgent, в Telegram уходит:

```text
TO: <addressing_key>
<текст пользователя>
```

Ключ — поле `id` (например `english_tutor`). В диалоге «Add new receiver» его можно задать вручную; иначе генерируется из display name.

## Запасной вариант в коде

Начальный каталог, если хранилище пустое:

`AndroidHermesCloudChat/app/src/main/java/com/nous/ahcc/domain/model/Addressee.kt` → `defaultCatalog`.

После первого запуска с UI список живёт в prefs (`addressee_catalog_json`).

## Завершение сессии с адресатом

После отправки **завершить / закончить / стоп / хватит** выбор снова **MainAgent** (сообщение уходит текущему адресату, затем сброс).
