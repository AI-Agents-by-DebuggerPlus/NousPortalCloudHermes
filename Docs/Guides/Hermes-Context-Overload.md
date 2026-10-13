# Перегрузка контекста Hermes

**Дата:** 2026-10-11  
**Связано:** расход токенов Sonnet (длинные сессии), AHCC 1.4.70+

## Суть

Каждый ход агент заново подаёт историю сессии в модель. Длинные диалоги, tool dumps и релеи раздувают input/cache. Hermes сам сжимает контекст, но **промежуточных предупреждений пользователю больше нет** (убраны, чтобы модель не «сдавалась» раньше времени).

Пороги на стороне Hermes:

| Слой | Порог | Действие |
|------|-------|----------|
| Agent compressor | ~50% окна (config `compression.threshold`) | Авто-сжатие середины истории |
| Gateway hygiene | ~85% | Аварийное сжатие перед ходом |
| Overflow | API reject | Попытка recovery / риск сброса |

## Что делать пользователю

1. **Новая задача → новая сессия** — в AHCC: «новая сессия» / New session (шлёт `/new now`).
2. **Долгая та же задача** — `/compress` (или `/compress here 2`).
3. **Проверить заполнение** — `/context` (alias `/ctx`).

## Footer с % в Telegram

По умолчанию footer выключен. Формат: `model · 68% · ~/…`

**В AHCC:** при готовности Telegram один раз уходит `/footer on` (без пузыря в чате). Дальше % парсится из ответов; при ≥50% / ≥70% / ≥85% показывается баннер.

**На Portal / хосте** (постоянно):

```yaml
display:
  runtime_footer:
    enabled: true
    fields: [model, context_pct]
```

Или в чате с ботом: `/footer on`.

## AHCC

- Slash без `TO:`: `/context`, `/ctx`, `/compress`, `/footer`, `/status`, `/usage`, `/new`, `/memory`.
- Баннер: Статус → `/context`, Сжать → `/compress`, Новая → «новая сессия».
- Footer не озвучивается TTS.
- **Авто-ротация:** если MainAgent отвечает только `{"skill":"new_session","resend":true}` (запрос не связан с историей), AHCC делает `/new` и пересылает тот же текст пользователя в новую сессию. См. [MainAgent.SOUL.md](../Agents/MainAgent.SOUL.md).

## Рекомендации по config

```yaml
compression:
  enabled: true
  threshold: 0.50
  # при желании дешёвая модель для summary:
# auxiliary:
#   compression:
#     model: "google/gemini-3-flash-preview"
```

Специалистов (EnglishTutor flashcards) держите в коротких сессиях или на отдельном профиле — не смешивайте с длинным MainAgent-тредом.
