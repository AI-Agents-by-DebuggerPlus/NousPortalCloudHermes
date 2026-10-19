# Карта Telegram-ботов — NousPortalCloudHermes (NPCH)

**Архитектура:** 1 Hermes-профиль ↔ 1 Telegram-бот ↔ свой ingress.  
**Префикс бренда:** `NPCH` (NousPortalCloudHermes).  
**Username-схема:** `npch_<profile_id>_bot` (латиница, lowercase, суффикс `_bot`).

Старые боты (`MyHermesBot`, `GoraAcademyFirstBot` и т.п.) не переиспользовать — создать заново в [@BotFather](https://t.me/BotFather), привязать token только к своему профилю, у `default`/`main_agent` не оставлять чужие токены.

---

## Сводная таблица

| # | Hermes profile `id` | Display (AHCC) | Bot name (видно в чате) | Username (заявка в BotFather) | About (коротко, ~120) |
|---|---------------------|----------------|-------------------------|-------------------------------|------------------------|
| 1 | `main_agent` | MainAgent | NPCH MainAgent | `@npch_main_agent_bot` | NousPortalCloudHermes — оркестратор и общий ассистент. |
| 2 | `assistant` | Assistant | NPCH Assistant | `@npch_assistant_bot` | NousPortalCloudHermes — повседневный помощник. |
| 3 | `english_tutor` | EnglishTutor | NPCH EnglishTutor | `@npch_english_tutor_bot` | NousPortalCloudHermes — English tutor / flashcards. |
| 4 | `task_scheduler` | TaskScheduler | NPCH TaskScheduler | `@npch_task_scheduler_bot` | NousPortalCloudHermes — планировщик задач и напоминаний. |
| 5 | `biostack` | BioStack | NPCH BioStack | `@npch_biostack_bot` | NousPortalCloudHermes — биохакинг / здоровье / стек. |
| 6 | `accountant` | Accountant | NPCH Accountant | `@npch_accountant_bot` | NousPortalCloudHermes — учёт, финансы, отчётность. |
| 7 | `sysadmin` | SysAdmin | NPCH SysAdmin | `@npch_sysadmin_bot` | NousPortalCloudHermes — ops, диск, сервер, инфра. |
| 8 | `trader` | Trader | NPCH Trader | `@npch_trader_bot` | NousPortalCloudHermes — спот/исполнение, Binance Demo. |
| 9 | `futures_trader` | FuturesTrader | NPCH FuturesTrader | `@npch_futures_trader_bot` | NousPortalCloudHermes — USDT-M фьючерсы, Demo. |
| 10 | `operator` | Operator | NPCH Operator | `@npch_operator_bot` | NousPortalCloudHermes — исполнитель действий. |

Если `main_agent` на Cloud ещё не создан как отдельный профиль, временно привязать `@npch_main_agent_bot` к профилю **`default`** (front desk), затем перенести CHANNELS на `main_agent`.

---

## Карточки для BotFather

Для каждого бота: `/newbot` → Name → Username → затем `/setdescription`, `/setabouttext`, `/setuserpic` (опционально).

Общий шаблон **Description** (длинное, до ~512):

> Official agent bot for **NousPortalCloudHermes** (NPCH).  
> Profile: `<DisplayName>` (`<profile_id>`).  
> Talk here for this agent only — not a shared router.  
> Instance: CloudHermesAgent / AHCC.

### 1. MainAgent — `@npch_main_agent_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH MainAgent` |
| Username | `npch_main_agent_bot` |
| About | `NousPortalCloudHermes — оркестратор и общий ассистент.` |
| Description | `NPCH MainAgent — front desk / orchestrator for NousPortalCloudHermes. General questions, planning, and coordinating specialists. Direct DM only for this profile.` |

### 2. Assistant — `@npch_assistant_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH Assistant` |
| Username | `npch_assistant_bot` |
| About | `NousPortalCloudHermes — повседневный помощник.` |
| Description | `NPCH Assistant — day-to-day helper for NousPortalCloudHermes. General assistance without trading or infra duties.` |

### 3. EnglishTutor — `@npch_english_tutor_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH EnglishTutor` |
| Username | `npch_english_tutor_bot` |
| About | `NousPortalCloudHermes — English tutor / flashcards.` |
| Description | `NPCH EnglishTutor — English learning agent for NousPortalCloudHermes (lessons, flashcards, practice).` |

### 4. TaskScheduler — `@npch_task_scheduler_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH TaskScheduler` |
| Username | `npch_task_scheduler_bot` |
| About | `NousPortalCloudHermes — планировщик задач и напоминаний.` |
| Description | `NPCH TaskScheduler — scheduling and reminders agent for NousPortalCloudHermes.` |

### 5. BioStack — `@npch_biostack_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH BioStack` |
| Username | `npch_biostack_bot` |
| About | `NousPortalCloudHermes — биохакинг / здоровье / стек.` |
| Description | `NPCH BioStack — health / biohacking stack agent for NousPortalCloudHermes.` |

### 6. Accountant — `@npch_accountant_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH Accountant` |
| Username | `npch_accountant_bot` |
| About | `NousPortalCloudHermes — учёт, финансы, отчётность.` |
| Description | `NPCH Accountant — bookkeeping and finance agent for NousPortalCloudHermes.` |

### 7. SysAdmin — `@npch_sysadmin_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH SysAdmin` |
| Username | `npch_sysadmin_bot` |
| About | `NousPortalCloudHermes — ops, диск, сервер, инфра.` |
| Description | `NPCH SysAdmin — infrastructure / ops agent for NousPortalCloudHermes (disk, host, gateway).` |

### 8. Trader — `@npch_trader_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH Trader` |
| Username | `npch_trader_bot` |
| About | `NousPortalCloudHermes — спот/исполнение, Binance Demo.` |
| Description | `NPCH Trader — spot / execution & risk agent for NousPortalCloudHermes (Binance Demo). Not futures specialist.` |

### 9. FuturesTrader — `@npch_futures_trader_bot`

| Поле | Значение |
|------|----------|
| Name | `NPCH FuturesTrader` |
| Username | `npch_futures_trader_bot` |
| About | `NousPortalCloudHermes — USDT-M фьючерсы, Demo.` |
| Description | `NPCH FuturesTrader — USDT-M futures agent for NousPortalCloudHermes (Binance Futures Demo).` |

---

## Привязка на Cloud (чеклист)

На каждый профиль:

1. BotFather → скопировать **token** (не коммитить в git).
2. Portal → PROFILES → профиль → **CHANNELS** → Telegram → вставить token.
3. Убедиться: **этот token нигде больше** (не у `default` и не у соседнего профиля).
4. Restart gateway для профиля (или instance, по практике Cloud).
5. В обычном Telegram: Start у бота → проверить ответ SOUL.
6. В AHCC: в каталоге адресата указать username бота (после доработки multi-bot клиента).

Хранение token’ов: локально `Docs/Credentials/` (gitignore) или секретница Portal — **не** в репозиторий.

Пример строки учёта (локально):

```text
profile_id=<id>
bot_username=@npch_<id>_bot
bot_name=NPCH <Display>
token=<from BotFather>
bound_at=YYYY-MM-DD
```

---

## AHCC (после создания ботов)

Целевой каталог адресата:

| AHCC display | profile id | telegram bot |
|--------------|------------|--------------|
| MainAgent | `main_agent` | `@npch_main_agent_bot` |
| Assistant | `assistant` | `@npch_assistant_bot` |
| EnglishTutor | `english_tutor` | `@npch_english_tutor_bot` |
| TaskScheduler | `task_scheduler` | `@npch_task_scheduler_bot` |
| BioStack | `biostack` | `@npch_biostack_bot` |
| Accountant | `accountant` | `@npch_accountant_bot` |
| SysAdmin | `sysadmin` | `@npch_sysadmin_bot` |
| Trader | `trader` | `@npch_trader_bot` |
| FuturesTrader | `futures_trader` | `@npch_futures_trader_bot` |

Пока клиент одноботовый — можно временно оставить один рабочий бот и подключать остальных по мере готовности CHANNELS + multi-chat в AHCC.

---

## Если username занят

Добавить суффикс instance, например:

- `npch_main_agent_4280_bot`
- `npch_trader_ch_bot`

Name и About оставить с префиксом **NPCH**, чтобы бренд читался даже при другом username.

---

## Связанные документы

- [AHCC-Addressees.md](./AHCC-Addressees.md)
- [Hermes-Inter-Profile-Messaging.md](../Reports/Hermes-Inter-Profile-Messaging.md)
- [Hermes-MultiAgent-Routing-Implementation-Plan.md](./Hermes-MultiAgent-Routing-Implementation-Plan.md) — legacy «один bot на default»; эта карта — целевая замена
