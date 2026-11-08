-- Run in Supabase → SQL Editor. SAFE TO RE-RUN (idempotent). Upgrades v1 → v2.
create extension if not exists pgcrypto;

create table if not exists images(
  id uuid primary key default gen_random_uuid(),
  captured_at timestamptz not null,
  camera text not null, source text default 'auto',            -- auto | manual | auto-retry | shake | chat
  status text default 'pending',                               -- pending | processing | done | failed
  storage_path text, interpretation text, activity text, usable boolean, confidence text,
  tags text[], error text, attempts int default 0,
  retry_front boolean default false, retry_handled boolean default false,
  processed_at timestamptz, created_at timestamptz default now());
create index if not exists images_q on images(status, captured_at);
alter table images add column if not exists rules_hit text[];
alter table images add column if not exists observed boolean;   -- old rows stay NULL (ignored); new rows false until the phone engine has judged them
alter table images alter column observed set default false;

create table if not exists goals(id uuid primary key default gen_random_uuid(), title text not null, plan text, active boolean default true, created_at timestamptz default now());
create table if not exists calls(
  id uuid primary key default gen_random_uuid(), status text default 'ringing',
  opening_message text, reason text, image_id uuid, summarized boolean default false,
  created_at timestamptz default now(), ended_at timestamptz);
create table if not exists messages(
  id uuid primary key default gen_random_uuid(), call_id uuid references calls(id) on delete cascade,
  role text not null, content text not null, status text default 'pending', end_call boolean default false, created_at timestamptz default now());
create table if not exists memories(
  id uuid primary key default gen_random_uuid(), happened_at timestamptz default now(), kind text, content text not null, tags text[],
  fts tsvector generated always as (to_tsvector('english', coalesce(content,''))) stored, created_at timestamptz default now());
create index if not exists memories_fts on memories using gin(fts);

-- ── v2: goals / tasks / habits / tiny rules (one table) ──
create table if not exists items(
  id uuid primary key default gen_random_uuid(),
  kind text default 'task',                 -- goal | task | habit | rule
  title text not null, details text,
  priority int default 2,                   -- 1 low · 2 medium · 3 high
  status text default 'active',             -- active | paused | done | failed | deleted
  deadline timestamptz, target_min int default 0,
  acts text[] default '{}', keywords text[] default '{}', avoid text[] default '{}',
  win_start int, win_end int,               -- minute-of-day window (e.g. sleep 23:00-03:00)
  parent_id uuid, created_at timestamptz default now(), updated_at timestamptz default now());
create table if not exists events(          -- goal progress / completed / failed / postponed
  id uuid primary key default gen_random_uuid(), item_id uuid, ts timestamptz default now(),
  type text, minutes int default 0, note text);
create index if not exists events_ts on events(ts);
create table if not exists activity(        -- structured activity records (not just pictures)
  id uuid primary key default gen_random_uuid(), ts timestamptz default now(), source text, activity text, description text,
  minutes int default 0, wasted boolean default false, intentional boolean default false, item_ids text[], image_id uuid);
create index if not exists activity_ts on activity(ts);
create table if not exists decisions(       -- every AI decision: note | remind | remind_later | interrupt | rule
  id uuid primary key default gen_random_uuid(), ts timestamptz default now(), action text, item_id uuid, reason text,
  activity text, message text, due_at timestamptz, handled boolean default false, image_id uuid);
create index if not exists decisions_ts on decisions(ts);
create table if not exists usage(           -- per-app seconds per day, phone + laptop
  day date not null, device text not null, app_key text not null, label text, seconds int default 0, updated_at timestamptz default now(),
  primary key(day, device, app_key));
create table if not exists devices(id text primary key, device text, label text, pair_code text, last_seen timestamptz default now());

-- one-time migration of old goals
insert into items(kind,title,details,status) select 'goal', title, plan, case when active then 'active' else 'paused' end from goals g
 where not exists(select 1 from items);

do $$ declare t text; begin
  foreach t in array array['images','goals','calls','messages','memories','items','events','activity','decisions','usage','devices'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists "anon all" on %I', t);
    execute format('create policy "anon all" on %I for all to anon using (true) with check (true)', t);
  end loop; end $$;

-- ── v3: companion plumbing (contextual ledger, device activity, audio clips, chat registry) ──
alter table images add column if not exists ctx text;            -- phone state at capture: screen, foreground app, recent apps
alter table images add column if not exists question text;       -- the curious question Gemini wants to ask (optional)
alter table decisions add column if not exists made_by text;       -- 'gemini' = decided by the brain; delivered by the phone
create table if not exists app_log(                              -- foreground-app / website segments (phone + laptop)
  id text primary key, device text not null, app_key text, label text, title text,
  start_ts timestamptz not null, end_ts timestamptz not null, seconds int default 0);
create index if not exists app_log_t on app_log(end_ts);
create table if not exists clips(                                -- keyword-triggered audio clips (queue = status column)
  id uuid primary key default gen_random_uuid(), started_at timestamptz, ended_at timestamptz, trigger text, transcript text,
  storage_path text, ctx text, status text default 'pending',    -- pending | processing | done | failed
  interpretation text, state text, tone text, attempts int default 0, error text, observed boolean default false,
  processed_at timestamptz, created_at timestamptz default now());
create index if not exists clips_q on clips(status, started_at);
create table if not exists chats(                                -- every Gemini web chat used (50-item rotation, 6-month recall)
  id uuid primary key default gen_random_uuid(), role text, url text, seq int default 1, items int default 0,
  first_ts timestamptz default now(), last_ts timestamptz default now(), closed boolean default false);
do $$ declare t text; begin
  foreach t in array array['app_log','clips','chats'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists "anon all" on %I', t);
    execute format('create policy "anon all" on %I for all to anon using (true) with check (true)', t);
  end loop; end $$;

insert into storage.buckets(id,name,public) values('captures','captures',true) on conflict do nothing;
drop policy if exists "anon upload" on storage.objects; drop policy if exists "anon update" on storage.objects; drop policy if exists "anon read" on storage.objects;
create policy "anon upload" on storage.objects for insert to anon with check (bucket_id='captures');
create policy "anon update" on storage.objects for update to anon using (bucket_id='captures');
create policy "anon read"   on storage.objects for select to anon using (bucket_id='captures');
