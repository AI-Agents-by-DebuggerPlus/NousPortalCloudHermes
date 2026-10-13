# WordPress: AHCC Flashcards (Supabase)

## Goal

Shortcode **`[ahcc_flashcards]`** — read Hermes replies from **`ahcc_messages`**, parse `{"type":"flashcard","en","ru"}`, show AHCC-like UI **without TTS**.

**Status: done (plugin v1.0.0)** — folder `WordPress/AhccFlashcards/`, installable zip `WordPress/ahcc-flashcards-1.0.0.zip`.

## Data

| Source | Filter |
|--------|--------|
| `public.ahcc_messages` | `session_id` (setting / shortcode / `?ahcc_session=` / latest), `sender_name` ≈ Hermes, flashcard JSON in `content` |

Dedupe cards by `en`+`ru`, order by `created_at`.

## Plugin layout

```
WordPress/AhccFlashcards/
  ahcc-flashcards.php          bootstrap, shortcode
  uninstall.php
  includes/class-supabase.php  PostgREST (server-side, anon key in wp_options)
  includes/class-parser.php    extract flashcards from content
  includes/class-ajax.php      poll + admin Test Supabase
  admin/class-settings.php     Settings → Supabase, session, poll, display
  admin/admin.js
  public/css/flashcards.css
  public/js/flashcards.js      navigation, blank timer, poll, no audio
```

## UX (matches AHCC, no voice)

- Full-viewport black (or configured) background, immersive-friendly CSS
- EN top, RU below (top padding + gap from settings)
- Bottom bar: countdown, card count, prev/next, fullscreen
- **Tap / Space** = show card (exit blank); **←/→** = prev/next card
- Auto **blank** after N seconds (default 60)

## Security

- Supabase **anon** key stored in WP options; requests only from **PHP** (`wp_remote_get` / AJAX handlers), not exposed in HTML.
- Cards JSON passed to JS via `wp_localize_script` (no key in browser).

## Phase 2 — implemented

- Poll Supabase for new cards (AJAX `ahcc_flashcards_poll`)
- Per-user session: shortcode `session_id`, URL `?ahcc_session=`, settings default
- Display settings aligned with AHCC `FlashcardDisplaySettings` (admin + localize)
