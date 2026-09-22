# AHCC ↔ Supabase sessions + files

Shared session, chat history, and text/markdown file transfer for **AHCC**, **AHCCD**, **AHCCDV**.

AHCCDV talks to **Supabase only** (no Hermes Cloud WebSocket).

## Tables

### `public.ahcc_messages`

```sql
create table if not exists public.ahcc_messages (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid null,
  sender_name text not null,
  content text not null,
  created_at timestamptz not null default now(),
  recipient_name text null,
  session_id text not null
);

create index if not exists ahcc_messages_created_at_desc
  on public.ahcc_messages (created_at desc);

create index if not exists ahcc_messages_session_created
  on public.ahcc_messages (session_id, created_at);

alter table public.ahcc_messages enable row level security;

create policy "ahcc_messages_select_anon"
  on public.ahcc_messages for select to anon using (true);

create policy "ahcc_messages_insert_anon"
  on public.ahcc_messages for insert to anon with check (true);
```

### `public.ahcc_files` (md / text share)

```sql
create table if not exists public.ahcc_files (
  id uuid primary key,
  session_id text not null,
  sender_name text not null,
  file_name text not null,
  mime text not null default 'text/markdown',
  content text not null,
  byte_size int not null default 0,
  created_at timestamptz not null default now()
);

create index if not exists ahcc_files_session_created
  on public.ahcc_files (session_id, created_at);

alter table public.ahcc_files enable row level security;

create policy "ahcc_files_select_anon"
  on public.ahcc_files for select to anon using (true);

create policy "ahcc_files_insert_anon"
  on public.ahcc_files for insert to anon with check (true);
```

## Semantics

| Event | Where |
|-------|--------|
| New session | `ahcc_messages.content = 'new session'` |
| Chat text | `ahcc_messages` + `session_id` |
| Shared `.md` / text | row in `ahcc_files` + chat marker `[AHCC_FILE]{"id","name","mime"}` in `ahcc_messages` |

**Last session** = last `ahcc_messages` row by `created_at` → `session_id`.

## Clients

- **AHCC / AHCCD**: create/resume session; write messages; attach `.md` → Supabase file + marker.
- **AHCCDV**: loads last session history; Refresh / poll; click `[file]` to save `.md`. No Hermes Cloud WS.

## Limits

Text/markdown share max **512 KiB** per file.
