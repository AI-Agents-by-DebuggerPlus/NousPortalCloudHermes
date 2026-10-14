# AHCC — адресаты сообщений

## Список получателей

- **В чате (Android и AHCC Desktop):** dropdown «Адресат», кнопки **Delete current** / **Add new** / **Set default**.
- Список сохраняется в DataStore на телефоне; на Desktop — в `~/.ahcc-desktop/ui.properties`.
- **MainAgent** (`main_agent`) — начальный дефолт, удалить нельзя; без `TO:` только у `main_agent`.
- Встроенные specialists: **Assistant** (`assistant`), **EnglishTutor** (`english_tutor`), **TaskScheduler** (`task_scheduler`), **BioStack** (`biostack`), **Accountant** (`accountant`), **SysAdmin** (`sysadmin`; ops/диск), **Trader** (`trader`; исполнение ордеров + риск/ММ, Binance Demo). SOUL: `Docs/Agents/*.SOUL.md`. Setup Trader: [Trader-Profile-and-Binance-Demo-Setup.md](./Trader-Profile-and-Binance-Demo-Setup.md).
- Кнопка **Set as default** — любой адресат может стать дефолтным (выбор при старте и после «завершить/стоп/…»).

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

После отправки **завершить / закончить / стоп / хватит** выбор снова **дефолтный адресат** (сообщение уходит текущему адресату, затем сброс).
