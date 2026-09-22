## Cloud Hermes + AHCC Desktop Viewer (WebSocket)

AHCCDV connects to the **dashboard** `wss://…/api/ws` (not `:8642`).

On the agent, set a pinned token and restart gateway:

```env
HERMES_DASHBOARD_SESSION_TOKEN=<long-secret>
```

Paste the same value into AHCCDV Settings → Auth token, then Connect.

Details: `AhccDesktopViewer/README.md`.

---

**Зачем:** при подключении AHCC должен загружать историю из **Hermes Session API**, а не из локального буфера.  
**Проблема:** в `Docs/Credentials/Credentials.md` есть только ключ **Nous Portal** (`sk-nous-…`). Им открывается Inference API, но **не** история сессий Cloud Agent.

---

## 1. Что уже есть и чего не хватает

| Артефакт | Есть в Credentials.md? | Для чего |
|----------|------------------------|----------|
| `sk-nous-…` | Да | `https://inference-api.nousresearch.com` — чат HTTP SSE |
| Host агента `cloudhermesagent-4280…` | Да | Dashboard / статус |
| `API_SERVER_KEY` | **Нет** | `GET/POST /api/sessions…`, история, session chat |
| Публичный доступ к Agent API `:8642` | **Нет** (слушает `127.0.0.1` внутри VM) | Клиент снаружи не достучится |

Проверено фактами:

- `wss://HOST/v1/ws/chat` → **403**
- `https://HOST/v1/chat/completions` → HTML **Sign in**
- `https://HOST/api/sessions/.../messages` + `Bearer sk-nous-…` → **401**
- Inference + `sk-nous` → **работает**

Документ Credentials смешивает Portal key и доступ к Agent API — для истории этого недостаточно.

---

## 2. Что сделать на стороне Hermes Agent (обязательно)

Нужен доступ к машине/контейнеру Cloud Hermes (SSH / dashboard terminal / провайдер Nous), где крутится агент.

### 2.1. Включить API Server и задать ключ

Нужен shell на машине агента (SSH / терминал Cloud Hermes / контейнер), пользователь с домашним каталогом Hermes (`~/.hermes/`).

#### Способ A — CLI (удобнее)

```bash
hermes config set API_SERVER_ENABLED true
hermes config set API_SERVER_KEY "$(openssl rand -hex 32)"

# Посмотреть, что записалось (ключ будет в .env):
grep API_SERVER ~/.hermes/.env

# Перезапуск gateway:
hermes gateway stop
hermes gateway
# или: hermes gateway restart
```

`hermes config set` кладёт флаг/секрет в `config.yaml` / `~/.hermes/.env` автоматически.

#### Способ B — руками в `~/.hermes/.env`

```env
API_SERVER_ENABLED=true
API_SERVER_PORT=8642
API_SERVER_HOST=127.0.0.1
API_SERVER_KEY=замените_на_длинный_секрет
```

Либо в `~/.hermes/config.yaml`:

```yaml
gateway:
  api_server:
    enabled: true
    port: 8642
    host: 127.0.0.1
    key: замените_на_длинный_секрет
```

Переменные окружения `API_SERVER_*` имеют приоритет над `config.yaml`.

#### Если агент уже запущен

На Cloud Hermes `api_server` часто уже `connected`. Тогда:

```bash
# Прочитать существующий ключ (не светите его в чат/git):
grep -E '^API_SERVER_' ~/.hermes/.env
```

Если `API_SERVER_KEY` уже есть — **используйте его** в AHCC / curl. Менять ключ нужно только если хотите ротацию (после смены — restart gateway).

Рекомендации:

- `API_SERVER_KEY` — отдельный секрет, **не** `sk-nous-…`.
- По умолчанию `API_SERVER_HOST=127.0.0.1` — с телефона/ПК снаружи не видно; для внешнего доступа нужен proxy/туннель (§2.2) или осторожный `0.0.0.0` за firewall.
- Документация: https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server

### 2.2. Открыть API снаружи (выберите один вариант)

**Вариант A — Reverse proxy на том же HTTPS-хосте** (предпочтительно для cloud):

- Проксируйте, например, `https://cloudhermesagent-4280.agents.nousresearch.com/agent-api/` → `http://127.0.0.1:8642/`.
- Либо отдельный поддомен/порт с TLS.
- В AHCC в поле **Agent API base URL** укажите этот публичный URL (без хвостового `/`).

**Вариант B — SSH-туннель с вашего ПК:**

```bash
ssh -L 8642:127.0.0.1:8642 user@<host-or-jump>
```

Тогда в AHCC Desktop:

- **Agent API base URL** = `http://127.0.0.1:8642`
- **API key** для истории = значение `API_SERVER_KEY`  
  (если для Inference нужен другой ключ — см. §3.2)

**Вариант C — локальный Hermes** на этой же машине: достаточно `http://127.0.0.1:8642` + `API_SERVER_KEY`.

### 2.3. Проверка с вашей машины

Подставьте свой base и ключ:

```bash
curl -sS -H "Authorization: Bearer $API_SERVER_KEY" \
  "$AGENT_API_BASE/v1/models"

curl -sS -H "Authorization: Bearer $API_SERVER_KEY" \
  "$AGENT_API_BASE/api/sessions?limit=5"

curl -sS -H "Authorization: Bearer $API_SERVER_KEY" \
  "$AGENT_API_BASE/api/sessions/android_test_session/messages?limit=50&order=oldest"
```

Ожидание: JSON **200**, не HTML Sign in и не 401.

Создание сессии, если её ещё нет:

```bash
curl -sS -X POST -H "Authorization: Bearer $API_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"android_test_session\",\"title\":\"AHCC\"}" \
  "$AGENT_API_BASE/api/sessions"
```

Официальная справка: [API Server | Hermes Agent](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server).

---

## 3. Что сделать в AHCC

### 3.1. Settings

| Поле | Значение |
|------|----------|
| **Session ID** | Тот же id, что на агенте (например `android_test_session` / `desktop_test_session`) |
| **Agent API base URL** | Публичный или tunneled base из §2.2 (не путать с Inference URL) |
| **API key (Bearer)** | Для истории/session chat — **`API_SERVER_KEY`**. Для Inference-чата по-прежнему может понадобиться `sk-nous-…` |

Сейчас в клиенте одно поле ключа. Практичные схемы:

1. **Пока тестируете историю:** временно поставить `API_SERVER_KEY`, Agent API base = reachable URL; Inference может отвалиться, пока ключи не разделены в UI.
2. **Рабочий режим:** доработать UI двумя ключами (Portal + Agent) — отдельная задача; до этого удобнее туннель + Agent key только когда нужна синхронизация истории с агентом.

### 3.2. Поведение при Connect

1. Auth к Inference (`sk-nous` → `/v1/models`).
2. Запрос истории: `GET {Agent API}/api/sessions/{sessionId}/messages`.
3. UI очищается и заполняется **ответом сервера**.
4. Если Agent API доступен — новые реплики предпочтительно идут в `POST …/api/sessions/{id}/chat/stream` (история остаётся на сервере).

Если шаг 2 даёт 401/таймаут — в чате будет system-заметка `Server history unavailable: …`.

### 3.3. Документация credentials

После появления `API_SERVER_KEY` и публичного base **допишите** в `Docs/Credentials/` (локально, не в публичный git):

```env
# Nous Portal — Inference
HERMES_API_KEY=sk-nous-…
INFERENCE_BASE_URL=https://inference-api.nousresearch.com

# Hermes Agent API — sessions / history
API_SERVER_KEY=…
AGENT_API_BASE_URL=https://…   # или http://127.0.0.1:8642 через туннель
```

Не коммитьте секреты в репозиторий.

---

## 4. Чеклист «готово»

- [ ] `API_SERVER_ENABLED=true`, задан `API_SERVER_KEY`
- [ ] API доступен с клиента (proxy / tunnel / local)
- [ ] `curl …/api/sessions/{id}/messages` → 200 + JSON
- [ ] В AHCC указаны Session ID + Agent API base URL + верный Bearer для Agent API
- [ ] Connect → в UI появляются сообщения с сервера (или пустой список, если сессия новая)
- [ ] После перезапуска приложения история снова подтягивается с сервера

---

## 5. Краткий вывод

**Credentials.md достаточно для Inference-чата.**  
**Для серверной истории нужна отдельная настройка Agent API (`API_SERVER_KEY` + сетевой доступ к `:8642` / proxy).** Без этого AHCC правильно падает в «history unavailable» — это не баг клиента, а ограничение облачного доступа.
