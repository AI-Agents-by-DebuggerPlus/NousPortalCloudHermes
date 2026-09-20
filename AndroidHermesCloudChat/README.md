# AndroidHermesCloudChat (AHCC)

Android client for Hermes Agent over WebSocket, based on `Docs/Gemini/HermesAndroidChat`.

## Stack

- Kotlin · Jetpack Compose · MVVM / Clean Architecture
- Ktor Client (CIO + WebSockets + Kotlinx Serialization)
- Foreground Service + WakeLock for background keep-alive
- Exponential backoff reconnect (max 30s)

## Protocol

- Endpoint: `ws(s)://<host>:<port>/v1/ws/chat?session_id=<id>`
- Auth header: `Authorization: Bearer <apiKey>`
- Outbound: `{ "event": "message", "session_id", "content", "data": { "role": "user", "content" } }`
- Inbound events: `token` / `message.delta`, `tool_call`, `done` / `message.complete`, `error`

## Build & run

```bash
cd AndroidHermesCloudChat
./gradlew assembleDebug
./gradlew installDebug
```

Open **Settings**, set host / port / API key from Nous Portal, then tap the link icon to connect.

## Desktop

```bash
cd AndroidHermesCloudChat
./gradlew :desktop:run
```

Desktop uses the same Nous Inference HTTP SSE transport. **Enter** sends, **Shift+Enter** inserts a newline.
