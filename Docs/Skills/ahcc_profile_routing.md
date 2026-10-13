---
name: ahcc-profile-routing
description: "Relay TO: messages to the addressed Hermes profile."
version: 1.0.0
---

# AHCC profile routing (MainAgent front desk)

AHCC / the web dashboard posts messages to the MainAgent chat. When the first line is
`TO: <name>` (case-sensitive, one token on that line), everything after the blank line is
the user payload for the Hermes profile named `<name>`.

## Rule

- `<name>` == `main_agent` (or no `TO:` line) -> answer normally yourself.
- otherwise -> do NOT answer the payload from your own reasoning; **relay it to that
  profile's agent and return its reply**.
- Never tell the user which addressee to pick, and add no meta-commentary about routing.

## How to relay (local transport)

Profiles are full Hermes installs under `/opt/data/profiles/<name>/` and are reachable
two ways:

1. **CLI (works, proven)** - write the payload to a **new** temp file each turn (never
   inline shell-quote arbitrary text), then:

   ```bash
   /opt/hermes/.venv/bin/hermes -p <name> chat \
     --in /opt/data/profiles/<name>/home \
     -c "Bot Chat" --create-if-missing -Q \
     --query-file /path/payload.txt
   ```

   Use a unique path per message, e.g.
   `/opt/data/cache/scratch/ahcc_payload_<unix_ts>.txt`, or `read_file` the full existing
   file before overwriting a fixed name. Reusing `ahcc_payload.txt` without a prior read
   triggers the **file-mutation verifier** and fails the turn even when the relay succeeded.

   The reply prints to stdout (minus the trailing `session_id:` line). Relay that text to
   the user. `--in` + `-c "Bot Chat"` reuses the profile's canonical Bot Chat session so the
   conversation is continuous; `--create-if-missing` makes it on first use.

2. **Peer DM over the local api_server** - the gateway listens on `127.0.0.1:8642`,
   profile mirror `/p/<name>/`, auth via `API_SERVER_KEY` from `/opt/data/.env`.
   `hermes peer dm` only works after `hermes peer add <peer> --url http://127.0.0.1:8642
   --key <API_SERVER_KEY>`. Prefer the CLI route (1); it needs no peer registration.

## Pitfalls

- Do NOT try to answer "as" the target profile yourself - its SOUL.md (e.g.
  `/opt/data/profiles/<name>/SOUL.md`) defines its own protocol (EnglishTutor emits
  `{"type":"flashcard",...}` JSON, session commands next/stop, etc.).
- Inline `-q "$PAYLOAD"` is unsafe for arbitrary text; always use `--query-file`.
- Commands that read secrets from `.env` or hit the api_server with a bearer key can trip
  approval prompts - the `hermes -p ... chat` CLI route avoids that.
- Relay the reply verbatim (including any JSON protocol lines) so the client can render it.
- Do not echo tool cards (“Writing…”, shell blocks) to the user — run the CLI, then paste
  **only** the subprocess stdout as your Telegram reply.
