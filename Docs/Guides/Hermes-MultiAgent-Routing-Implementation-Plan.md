# План реализации: несколько профилей + маршрутизация на `default`

**Контекст:** один Cloud instance (`CloudHermesAgent`), проверенный канал **Telegram** (AHCC → TDLib → DM с ботом → Hermes gateway).  
**Цель:** независимые профили (`english_tutor`, `task_scheduler`, …) с **одним** bot token на **`default`**, который логически выполняет роль router (forward в нужный `-p`).

**Связанные документы:**

- [Hermes-Inter-Profile-Messaging.md](../Reports/Hermes-Inter-Profile-Messaging.md) — механизмы связи между профилями
- [CloudHermes-MainAgent-EnglishTutor-Setup.md](./CloudHermes-MainAgent-EnglishTutor-Setup.md) — создание профилей на Portal
- [AHCC-Agent-Link-Protocol.md](../Reports/AHCC-Agent-Link-Protocol.md) — протокол клиента (`TO:`)
- [AHCC-Addressees.md](./AHCC-Addressees.md) — адресаты в AHCC
- Persona: [MainAgent.SOUL.md](../Agents/MainAgent.SOUL.md), [EnglishTutor.SOUL.md](../Agents/EnglishTutor.SOUL.md)
- Архитектурная дискуссия (Router PDF): [Развертывание мультиагентной системы.pdf](../ChatGPT/Развертывание%20мультиагентной%20системы.pdf)

**Не в scope этого плана:** отдельный Cloud **CREATE NEW INSTANCE** на каждого агента; отдельный Telegram-бот на каждый профиль (опционально в конце).

---

## Текущая база (на момент плана)

| Компонент | Статус |
|-----------|--------|
| Nous Portal instance | `CloudHermesAgent`, Medium, ONLINE |
| Профили | `default` (active, Telegram, gateway), `english_tutor`, `task_scheduler` |
| Профиль `main_agent` | Опционально; ingress можно оставить на `default` |
| AHCC | Telegram-канал; адресаты + `TO:` (id `main_agent`, `english_tutor`, …) |
| Router в Portal UI | Нет; маршрутизация — на **`default`** (SOUL + tool/shell) |

---

## Этап 0. Зафиксировать архитектурные решения

**Результат:** одна страница решений, без противоречий с клиентом.

| # | Решение | Рекомендация |
|---|---------|--------------|
| 0.1 | Сколько Cloud instance | **Один** `CloudHermesAgent` |
| 0.2 | Сколько Telegram-ботов | **Один** token на **`default`** |
| 0.3 | Кто ingress | Профиль **`default`** (или позже перенос на `main_agent` + перепривязка CHANNELS) |
| 0.4 | Формат адресации | Строка `TO: <profile_id>\n<body>` (совместимо с AHCC); позже — JSON в теле |
| 0.5 | Способ forward | **`hermes -p <profile> chat -q "…"`** из **`default`** (skill или shell), не `message_agent` в DM |
| 0.6 | MainAgent vs default | Пока **`default` = front desk**; id `main_agent` в AHCC = «без TO:» или self-route |

**Шаги:**

1. Подтвердить таблицу решений.
2. Записать whitelist id: `english_tutor`, `task_scheduler`, self (`default` / `main_agent`).

---

## Этап 1. Проверка связи между профилями (Cloud)

**Результат:** доказано, что с instance можно вызвать другой профиль и получить ответ.

| Шаг | Действие | Критерий успеха |
|-----|----------|-----------------|
| 1.1 | Dashboard → **SYSTEM** / Terminal (или SSH на VM, если есть) | Доступ к shell |
| 1.2 | `hermes profile list` | Видны `default`, `english_tutor`, `task_scheduler` |
| 1.3 | `hermes -p english_tutor chat -q "Представься одним предложением."` | Ответ от EnglishTutor |
| 1.4 | `hermes -p task_scheduler chat -q "Представься."` | Ответ от TaskScheduler |
| 1.5 | Зафиксировать путь к `hermes` (which hermes) | Для skill/subprocess |

**Если 1.3 падает:** KEYS/OpenRouter на профиле; SOUL; Support Portal — не переходить к этапу 3.

---

## Этап 2. Профили и persona (Portal)

**Результат:** каждый specialist ведёт себя предсказуемо при прямом `-p` вызове.

| Шаг | Профиль | Действие |
|-----|---------|----------|
| 2.1 | `english_tutor` | SOUL ← [EnglishTutor.SOUL.md](../Agents/EnglishTutor.SOUL.md) |
| 2.2 | `task_scheduler` | SOUL-заглушка: «Я TaskScheduler, тестовый агент, назначение TBD» |
| 2.3 | `default` | SOUL ← [MainAgent.SOUL.md](../Agents/MainAgent.SOUL.md) + блок **Router** (этап 3) |
| 2.4 | Все | MODEL: inherit / `deepseek/deepseek-v4.1-flash` (openrouter) или текущая |
| 2.5 | — | **Restart Gateway** после смены SOUL/config |

| Шаг | Проверка |
|-----|----------|
| 2.6 | Dashboard **Chat**, профиль `english_tutor` → flashcard / intro |
| 2.7 | Chat, `task_scheduler` → заглушка по имени |

**Опционально:** CREATE `main_agent` (clone default), позже перенос Telegram с `default` → `main_agent` (отдельный под-этап, не обязателен).

---

## Этап 3. Маршрутизация на `default`

**Результат:** сообщение из Telegram с `TO: english_tutor` обрабатывается профилем `english_tutor`; ответ в том же DM.

### 3A. CONFIG и tools

| Шаг | Действие |
|-----|----------|
| 3A.1 | **CONFIG** профиля **`default`**: terminal backend **local** (или разрешённый на Cloud) |
| 3A.2 | Tools: **shell/terminal** включены; политика approvals — по риску (single-query / deny лишнего) |
| 3A.3 | `gateway.multiplex_profiles: true` — **опционально** (для `profile_routes` по топикам; для `TO:` в DM не обязательно) |

### 3B. Registry (whitelist)

Задать в SOUL и/или skill:

```
english_tutor   → hermes -p english_tutor chat -q "<body>"
task_scheduler  → hermes -p task_scheduler chat -q "<body>"
(нет TO: или TO: default|main_agent) → ответ на default без forward
```

### 3C. SOUL router rules (минимум для `default`)

1. Разбор первой строки `TO: <id>`.
2. `<id>` только из whitelist; иначе короткая ошибка клиенту.
3. `<body>` — остаток после пустой строки.
4. Forward: **один** вызов CLI; ответ пользователю = **только** текст от specialist (без meta).
5. Не предлагать пользователю сменить адресата в AHCC.

### 3D. Skill `route_to_profile` (рекомендуется после 3C)

| Шаг | Действие |
|-----|----------|
| 3D.1 | Добавить skill на **`default`**: параметры `profile_id`, `message`; whitelist; subprocess |
| 3D.2 | SOUL: при `TO:` **обязательно** вызывать skill, не improvising shell |
| 3D.3 | Reload skills / restart gateway |

*(Конкретный файл skill — отдельная задача в репо или правка SKILLS на instance.)*

### 3E. CHANNELS

| Шаг | Действие |
|-----|----------|
| 3E.1 | **CHANNELS**: Telegram **только** у `default` |
| 3E.2 | У `english_tutor` / `task_scheduler` **нет** того же bot token |
| 3E.3 | **Restart Gateway** |

---

## Этап 4. Клиент AHCC (репозиторий)

**Результат:** исходящий текст согласован с router на `default`.

| Шаг | Действие | Статус / примечание |
|-----|----------|---------------------|
| 4.1 | Адресат MainAgent → сообщение **без** `TO:` | `main_agent` id в [Addressee.kt](../../AndroidHermesCloudChat/app/src/main/java/com/nous/ahcc/domain/model/Addressee.kt) |
| 4.2 | Остальные адресаты → `TO: <id>\n<text>` | `wireText()` |
| 4.3 | Каталог: `english_tutor`, `task_scheduler`, … | UI + DataStore |
| 4.4 | Документация протокола | [AHCC-Agent-Link-Protocol.md](../Reports/AHCC-Agent-Link-Protocol.md) |
| 4.5 | Сборка и установка AHCC на устройство | `gradlew installDebug` |

**Опционально (позже):** JSON-конверт в одном сообщении вместо `TO:`.

---

## Этап 5. Сквозное тестирование

| # | Сценарий | Ожидание |
|---|----------|----------|
| 5.1 | AHCC → MainAgent → «Привет» | Ответ от default/MainAgent SOUL, без forward |
| 5.2 | AHCC → EnglishTutor → «Кто ты?» | В Telegram первая строка `TO: english_tutor`; ответ как tutor |
| 5.3 | Portal → Sessions **`english_tutor`** | Новый turn после 5.2 |
| 5.4 | AHCC → TaskScheduler → тестовая фраза | Ответ заглушки scheduler |
| 5.5 | `TO: unknown` + текст | Короткая ошибка, без вызова shell |
| 5.6 | Голос + адресат EnglishTutor | Подпись/`TO:` или follow-up (зафиксировать фактическое поведение) |

**Регресс:** gateway Running; один bot; нет duplicate token warning в `hermes profile list`.

---

## Этап 6. Наблюдаемость и устойчивость

| Шаг | Действие |
|-----|----------|
| 6.1 | Логи gateway при forward (route id, latency) — по возможности Portal/SYSTEM |
| 6.2 | Таймаут subprocess < таймаут Telegram idle (~30s) |
| 6.3 | Ограничить длину `<body>` / ответа при необходимости |
| 6.4 | Документировать сбои: CLI not found, profile missing, model error |

---

## Этап 7. Опциональные улучшения (после стабильного MVP)

| Вариант | Когда |
|---------|--------|
| Профиль `main_agent` + перенос Telegram с `default` | Хотите каноническое имя = Hermes profile |
| Отдельный Telegram-бот **только** для `english_tutor` | Forward на default нестабилен; AHCC умеет второй chat_id |
| Skill-only router без рассуждений LLM на default | Экономия и предсказуемость |
| JSON `request_id`, `to`, `message` | Единый формат для будущих клиентов |
| Второй Cloud instance | Жёсткая изоляция uptime (дороже ~$0.56/day × N) |

---

## Чеклист «не делать»

- **CREATE NEW INSTANCE** для каждого агента без необходимости (дорого, сложнее ops).
- Один bot token на два профиля.
- Ожидать, что **`profile_routes`** разберёт `TO:` в одном личном DM.
- Полагаться на **`message_agent`** в обычном Telegram-turn без Bot Mode / Bot Chat.
- Коммитить секреты (bot token, api hash) в репозиторий.

---

## Порядок выполнения (кратко)

```text
Э0 Решения
 → Э1 CLI hermes -p … на Cloud
 → Э2 SOUL на профилях
 → Э3 CONFIG + SOUL/skill router на default + CHANNELS
 → Э4 AHCC (если ещё не выровнен)
 → Э5 E2E Telegram
 → Э6 Логи/таймауты
 → Э7 По необходимости
```

---

## История документа

| Дата | Изменение |
|------|-----------|
| 2026-10-01 | Первоначальный план: один instance, один bot, router на `default` |
