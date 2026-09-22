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

**WPF Launcher:** `AhccDesktopLauncher/` или `Launch-AhccDesktopLauncher.bat`

**Bat / VBS:**

- `Launch-AHCC-Desktop.bat` — console + Gradle run
- `Launch-AHCC-Desktop.vbs` — без окна консоли

Or from terminal:

```bash
cd AndroidHermesCloudChat
./gradlew :desktop:run
```

Desktop uses Nous Inference HTTP SSE. **Enter** sends, **Shift+Enter** newline, **paperclip** attaches a file (`AHCC_MEDIA_V1`). Header shows build version (`AppVersion.LABEL`).
