# Trader: профиль Hermes + Binance Demo

Цель: профиль **`trader`** (исполнитель: рынок, отложенные ордера, риск/ММ) на CloudHermesAgent и подключение к **демо-счёту Binance** для тестов.  
SOUL: [Docs/Agents/Trader.SOUL.md](../Agents/Trader.SOUL.md).

Секреты (API keys) **не коммитить**. Хранить только в env профиля на инстансе / локальном credentials вне git.

---

## Часть A — Binance Demo: как получить ключи

Binance разделил старый Spot Testnet и **Demo Trading**. Для paper-торговли через API используйте Demo.

### 1. Открыть Demo Trading

1. Войдите в аккаунт Binance (тот же логин, что основной).  
2. Откройте Demo UI: [https://demo.binance.com](https://demo.binance.com)  
3. Примите условия Demo Trading, если спросят.  
4. Убедитесь, что на демо-счёте есть виртуальный баланс (Spot и/или Futures — по тому, чем будете торговать).

### 2. Создать API keys **именно в Demo**

1. Перейдите в управление ключами Demo:  
   [https://demo.binance.com/en/my/settings/api-management](https://demo.binance.com/en/my/settings/api-management)  
2. **Create API** → имя, например `hermes-trader-demo`.  
3. Права для v0 исполнителя:
   - **Enable Reading** — да  
   - **Enable Spot & Margin Trading** — да, если торгуете Spot  
   - **Enable Futures** — да, если торгуете USDT-M / Coin-M Futures  
   - **Withdraw** — **нет** (не нужно для демо-исполнителя)  
4. Сохраните **API Key** и **Secret Key** сразу (Secret показывают один раз).  
5. При необходимости ограничьте IP инстанса Hermes (рекомендуется на VPS).

> Ключи с [testnet.binance.vision](https://testnet.binance.vision/) или с **production** Binance **несовместимы** с Demo endpoints. Для этой инструкции нужны ключи с `demo.binance.com` → API Management.

### 3. Base URL (подставлять в клиент/skill, не production)

| Рынок | REST base |
|-------|-----------|
| Spot Demo | `https://demo-api.binance.com` |
| USDT-M Futures Demo | `https://demo-fapi.binance.com` |
| Coin-M Futures Demo | `https://demo-dapi.binance.com` |

Аутентификация как у production (HMAC / заголовок `X-MBX-APIKEY`), но **только** demo-ключи + demo-хосты.

### 4. Быстрая проверка ключей (с машины, где будет Hermes)

Пример (подставьте свои значения; не логируйте secret):

```bash
# Spot Demo — account (нужен подписанный запрос; удобнее через python-binance / ccxt с sandbox/demo URL)
# Проверьте в UI Demo, что ордер/баланс видны после тестового вызова.
```

Если REST отвечает `Invalid API-key` — ключ не от Demo Management или указан production host.

---

## Часть B — Профиль `trader` на CloudHermesAgent

### 1. Создать профиль

**CLI на инстансе:**

```bash
hermes profile create trader --description "Trading executor: market/pending orders + risk/MM (Binance Demo)"
# или с клоном env/модели с default:
# hermes profile create trader --clone --description "…"
```

**Дашборд:** PROFILES → Create Profile → id **`trader`** (lowercase, как `TO: trader` в AHCC).

### 2. Вставить SOUL (Persona)

1. Откройте профиль **trader**.  
2. Persona / SOUL → вставьте целиком текст из репозитория:  
   [Docs/Agents/Trader.SOUL.md](../Agents/Trader.SOUL.md)  
3. Сохраните. В intro должна быть фраза вроде «Я Trader».

### 3. Модель

Как у других specialists (дешёвая/быстрая модель на ваш выбор):

```bash
hermes -p trader model set openrouter/<ваш-модель-id>
```

Или Dashboard → Model для профиля **trader**.

### 4. Переменные окружения профиля (ключи Binance + режим)

На инстансе в env профиля **trader** (путь зависит от деплоя; часто `/opt/data/profiles/trader/` + `.env` / secrets UI):

| Переменная | Пример / значение | Назначение |
|------------|-------------------|------------|
| `TRADER_MODE` | `demo` | `demo` (обязательно для тестов) или позже `live` |
| `BINANCE_API_KEY` | *(из Demo API Management)* | Публичный ключ |
| `BINANCE_API_SECRET` | *(secret)* | Секрет |
| `BINANCE_MARKET` | `spot` или `usdm` | Spot vs USDT-M Futures |
| `BINANCE_SPOT_BASE` | `https://demo-api.binance.com` | Spot Demo REST |
| `BINANCE_FAPI_BASE` | `https://demo-fapi.binance.com` | Futures Demo REST |
| `TRADER_MAX_NOTIONAL_USDT` | `200` | Макс. номинал одного ордера |
| `TRADER_MAX_OPEN_POSITIONS` | `3` | Макс. число открытых позиций |
| `TRADER_MAX_EXPOSURE_USDT` | `1000` | Макс. суммарная экспозиция |
| `TRADER_MAX_LEVERAGE` | `5` | Потолок плеча (futures) |
| `TRADER_DAILY_LOSS_USDT` | `100` | Дневной стоп по убытку |

Локальный шаблон без секретов: [Docs/Credentials/Binance_demo.env.example](../Credentials/Binance_demo.env.example).

После изменения env — перезапуск gateway / профиля по практике вашего инстанса.

### 5. Файл риск-лимитов (опционально)

Создайте на инстансе:

`/opt/data/profiles/trader/home/trader_risk.json`

```json
{
  "maxNotionalUsdt": 200,
  "maxOpenPositions": 3,
  "maxExposureUsdt": 1000,
  "maxLeverage": 5,
  "dailyLossUsdt": 100,
  "warnAtDailyLossPct": 80
}
```

Журнал сделок (агент пишет сам):  
`/opt/data/profiles/trader/home/trades.jsonl`

### 6. Инструменты агента (исполнение API)

Профиль должен уметь вызывать Binance REST (skill / shell / python на инстансе). Минимальный набор для v0:

- баланс / позиции  
- market order  
- limit (и stop, если включите)  
- cancel / status  

Пока отдельного skill-файла в репо нет — подключите проверенный способ на хосте (например скрипт `binance_demo_order.py` в `home/` профиля с чтением env). В SOUL запрещено «притворяться», что ордер прошёл, если вызова не было.

### 7. Роутинг `TO: trader`

Как у других specialists: AHCC шлёт в бота

```text
TO: trader
открой long BTCUSDT на 50 USDT по рынку
```

MainAgent с skill [ahcc_profile_routing](../Skills/ahcc_profile_routing.md) ретранслирует в `hermes -p trader chat …`.

Убедитесь, что gateway/default ingress и multiplex настроены так же, как для `english_tutor` / `assistant` (см. [CloudHermes-MainAgent-EnglishTutor-Setup.md](./CloudHermes-MainAgent-EnglishTutor-Setup.md)).

### 8. Проверка без телефона

```bash
hermes -p trader chat -q "Представься одним предложением."
```

Ожидание: ответ от Trader, упоминание Demo / исполнителя.

Затем (когда API tool готов):

```bash
hermes -p trader chat -q "Проверь баланс демо и риск-лимиты. Ордера не открывай."
```

---

## Часть C — AHCC / Desktop / клиенты

### 1. Каталог адресатов

В приложении должен быть адресат:

| id | Display |
|----|---------|
| `trader` | Trader |

В актуальных сборках AHCC / AHCC Desktop id уже в `defaultCatalog`. Если на устройстве старый список — **Add new** с id `trader` или очистка каталога адресатов (подтянется default).

### 2. Тест из Desktop / AHCC

1. To → **Trader**  
2. «Представься» → ответ Trader  
3. «Проверь риск-лимиты» → лимиты из env/json  
4. «Открой …» на демо → orderId + запись в `trades.jsonl`  
5. Запрос сверх лимита → **BLOCK** / предупреждение без ордера  

### 3. Что не путать

| Сессия | Смысл |
|--------|--------|
| TDLib клиента | Логин Telegram в AHCC/AET/Desktop |
| Hermes profile `trader` | Контекст и tools исполнителя |
| Binance Demo | Paper-счёт и demo API keys |

Сброс Telegram-сессии клиента **не** сбрасывает профиль `trader` и не меняет ключи Binance.

---

## Чеклист заполнения (кратко)

- [ ] Demo UI: [demo.binance.com](https://demo.binance.com)  
- [ ] API keys: [demo …/api-management](https://demo.binance.com/en/my/settings/api-management)  
- [ ] `hermes profile create trader`  
- [ ] SOUL из `Docs/Agents/Trader.SOUL.md`  
- [ ] Model для `-p trader`  
- [ ] Env: `TRADER_MODE=demo`, `BINANCE_*`, лимиты  
- [ ] (Опц.) `home/trader_risk.json`  
- [ ] Tool/скрипт ордеров на demo base URL  
- [ ] Роутинг `TO: trader` работает  
- [ ] В AHCC выбран Trader, тестовые команды прошли  

---

## Безопасность

- Production keys и `api.binance.com` / `fapi.binance.com` для v0 **запрещены** в SOUL.  
- Не присылайте API Secret в Telegram.  
- Withdraw на ключе — выключен.  
- Переход на live — отдельное решение: новые keys, `TRADER_MODE=live`, явное подтверждение в чате.
