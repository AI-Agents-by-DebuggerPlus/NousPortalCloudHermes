# AHCC Desktop Viewer (AHCCDV)

WPF app that shows the shared AHCC chat from **Supabase** (`ahcc_messages` / `ahcc_files`).  
**No Hermes Cloud WebSocket** — history and files only.

## Features

- Load last session from Supabase
- Refresh + optional poll
- Click `[file]` bubbles to save markdown/text
- Fullscreen — hotkey **F**
- Logs under `bin/.../logs/`

## Run

```bat
Launch-AhccDesktopViewer.bat
```

## Settings

| Field | Meaning |
|-------|---------|
| Session ID | From Supabase last row (read-only) |
| Supabase URL / anon key | Required |
| Poll interval | Seconds between auto-refresh (0 = off) |
| Connect on launch | Auto-load |

See `Docs/Guides/AHCC-Supabase-Sessions.md` for SQL (`ahcc_messages`, `ahcc_files`).
