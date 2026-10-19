# AHCC — адресаты сообщений

## Список получателей

- **В чате (Android и AHCC Desktop):** dropdown «Адресат», кнопки **Delete current** / **Add new** / **Set default**.
- Список сохраняется в DataStore на телефоне; на Desktop — в `~/.ahcc-desktop/ui.properties`.
- **MainAgent** (`main_agent`) — начальный дефолт, удалить нельзя; без `TO:` только у `main_agent`.
- Встроенные specialists: **Assistant** (`assistant`), **EnglishTutor** (`english_tutor`), **TaskScheduler** (`task_scheduler`), **BioStack** (`biostack`), **Accountant** (`accountant`), **SysAdmin** (`sysadmin`; ops/диск), **Trader** (`trader`; исполнение + риск/ММ, Binance Demo), **FuturesTrader** (`futures_trader`; только фьючерсы USDT-M Demo), **Operator** (`operator`; исполнитель действий). SOUL: `Docs/Agents/*.SOUL.md`. Боты: [AHCC-Telegram-Bots-Map.md](./AHCC-Telegram-Bots-Map.md). Setup: [Trader-Profile-and-Binance-Demo-Setup.md](./Trader-Profile-and-Binance-Demo-Setup.md), [FuturesTrader-Profile-and-Binance-Demo-Setup.md](./FuturesTrader-Profile-and-Binance-Demo-Setup.md).
- **Desktop:** у адресата можно задать свой Telegram bot username (Settings → Addressees). Если бот задан — сообщение уходит в его чат **без** `TO:`. Если пусто — fallback-бот из Connection + `TO:` для specialists.
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
