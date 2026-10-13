# CloudHermesAgent: профили main_agent и english_tutor

Цель: два изолированных Hermes-профиля с id, совпадающими с ключами AHCC (`TO: main_agent` не шлётся; шлётся `TO: english_tutor`).

## 1. Создание профилей

### CLI на инстансе (SSH / терминал Portal)

```bash
hermes profile create main_agent --description "Единственная точка контакта, роутер"
hermes profile create english_tutor --description "Генерация flashcards по английскому"
```

Опционально скопировать ключи/модель с текущего default:

```bash
hermes profile create main_agent --clone --description "…"
hermes profile create english_tutor --clone --description "…"
```

### Дашборд

**PROFILES → Create Profile** — имена **`main_agent`** и **`english_tutor`** (lowercase, как в AHCC).

## 2. SOUL.md

Скопировать содержимое из репозитория:

| Профиль | Файл в репо |
|---------|-------------|
| main_agent | [Docs/Agents/MainAgent.SOUL.md](../Agents/MainAgent.SOUL.md) |
| english_tutor | [Docs/Agents/EnglishTutor.SOUL.md](../Agents/EnglishTutor.SOUL.md) |

В дашборде: переключатель профиля → Persona / SOUL → вставить текст.

## 3. Модель (OpenRouter)

В контексте каждого профиля:

```bash
hermes -p main_agent model set openrouter/deepseek/deepseek-chat-v3-0324  # пример; подставьте вашу дешёвую модель
hermes -p english_tutor model set openrouter/deepseek/deepseek-chat-v3-0324
```

Или через дашборд → Model для выбранного профиля.

## 4. Gateway / ingress

Сообщения AHCC идут в **один** Telegram-чат с ботом. Профиль, который обрабатывает DM, задаётся активным/default gateway-профилем и/или `gateway.profile_routes` / роутером `TO:` на Cloud (см. [Hermes-Inter-Profile-Messaging.md](../Reports/Hermes-Inter-Profile-Messaging.md)).

Минимум для теста адресации:

1. Убедиться, что **english_tutor** существует и в SOUL есть фраза «Я EnglishTutor».
2. Из AHCC выбрать **EnglishTutor** → в Telegram должна уйти первая строка `TO: english_tutor`.
3. В дашборде открыть **Sessions** для профиля **english_tutor** и проверить, появился ли turn (если Cloud маршрутизирует по `TO:`).

Если turn остаётся только у **default/main_agent**, на инстансе нужна настройка маршрутизации (multiplex / attendant / support Nous Portal) — без неё клиент шлёт правильный `TO:`, но ingress-профиль один.

Рекомендуется для front-desk:

```bash
hermes profile use main_agent
# перезапуск gateway по документации инстанса
```

## 5. Проверка без телефона

```bash
hermes -p english_tutor chat -q "Представься одним предложением"
```

Ответ должен идентифицировать EnglishTutor.

## 6. AHCC

После обновления приложения в каталоге: **MainAgent** (`main_agent`), **EnglishTutor** (`english_tutor`). Старый id `liaison` на телефоне мигрируется в `main_agent` при чтении каталога.
