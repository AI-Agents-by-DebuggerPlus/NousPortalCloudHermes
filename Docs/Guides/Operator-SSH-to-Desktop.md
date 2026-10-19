# Operator → ваш ПК по SSH

**Цель:** профиль `operator` на CloudHermesAgent может заходить по SSH на `DESKTOP-TDDOEUF` (Windows) и запускать локальные программы (например Stamina).

## Что уже сделано на ПК (2026-10-19)

| Шаг | Статус |
|-----|--------|
| OpenSSH Server | установлен, `sshd` = Running (Automatic) |
| Firewall TCP 22 | разрещено |
| Пользователь | `busin` (Administrators) |
| Ключ | `~/.ssh/operator_hermes_ed25519` (+ `.pub`) |
| Auth | pubkey в `C:\ProgramData\ssh\administrators_authorized_keys` |
| Локальный тест | `ssh -i … busin@127.0.0.1` → OK |

LAN IP: **192.168.1.72** (домашняя сеть).  
Tailscale (оба Connected, 2026-10-19):

| Узел | Имя в console | Tailscale IP |
|------|---------------|--------------|
| Windows ПК | `desktop-tddoeuf` | **100.90.33.68** |
| Cloud / Operator | `hermes-operator-server` | **100.93.76.65** |

Сеть Cloud → дом через Tailscale **готова**. Дальше — ключ на профиль Operator и проверка SSH.

### Запасные варианты (если Tailscale недоступен)

- Cloudflare Tunnel / ngrok: `tcp://localhost:22` → публичный host.
- Проброс порта на роутере → `192.168.1.72:22` (хуже по безопасности).

## Ключ на профиль Operator (Cloud)

1. Скопировать **приватный** ключ с ПК:
   - путь: `C:\Users\busin\.ssh\operator_hermes_ed25519`
   - также: `Docs/Credentials/operator-ssh-desktop.key` (gitignore)
2. Portal → profile **`operator`** → terminal / files:
   - положить в `/opt/data/profiles/operator/home/.ssh/id_ed25519` (или `operator_desktop`)
   - `chmod 600` на ключ, `700` на `.ssh`
3. `~/.ssh/config` на Operator (пример после Tailscale):

```sshconfig
Host desktop-stamina
  HostName 100.90.33.68
  User busin
  IdentityFile ~/.ssh/id_ed25519
  IdentitiesOnly yes
```

4. Проверка из чата Operator:  
   `ssh desktop-stamina "hostname && whoami"`

## Stamina

После SSH:

```bash
ssh desktop-stamina "…путь к Stamina…"
```

Нужен полный путь к exe/скрипту на Windows (уточнить у пользователя). Пример:

```text
"C:\Program Files\Stamina\Stamina.exe"
```

или bat/ps1, который его запускает.

## Безопасность

- Не коммитить приватный ключ.
- Не включать PasswordAuthentication наружу без необходимости.
- Для admin-пользователей Windows OpenSSH читает **`administrators_authorized_keys`**, не только `~\.ssh\authorized_keys`.

## Сообщение Operator’у (после сети)

```text
SSH к моему ПК настроен. Host: desktop-stamina (см. ~/.ssh/config).
User: busin. Ключ: ~/.ssh/id_ed25519.
Проверь: ssh desktop-stamina "hostname".
Потом найди/запусти Stamina по пути, который я укажу.
```
