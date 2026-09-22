-- WN review: the schema the site expects. Run once in a new project's SQL editor
-- (Supabase dashboard > SQL > new query), then put the project's URL and anon key in
-- the site's VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY. Safe to run again.

create extension if not exists pgcrypto;

-- One row per filmed release: the manifest tools/release_build.py wrote, and where
-- the film and thumbnails are served from.
create table if not exists public.releases (
  id          uuid primary key default gen_random_uuid(),
  slug        text unique not null,
  name        text not null,
  notes       text default '',
  created_at  timestamptz not null default now(),
  video_url   text not null,
  thumb_base  text default '',
  manifest    jsonb not null
);

-- One row per comment, on one state (slide + letter) of one release.
create table if not exists public.comments (
  id          uuid primary key default gen_random_uuid(),
  release_id  uuid not null references public.releases(id) on delete cascade,
  -- 'comment', or 'voiceover' for a proposed voice-over text (the body is the whole text)
  kind        text not null default 'comment',
  -- 'visual' or 'audio': which side of the state the remark is about
  topic       text not null default 'visual',
  slide_id    text not null,
  state_key   text not null,
  author      text not null,
  body        text not null,
  created_at  timestamptz not null default now(),
  done        boolean not null default false,
  done_by     text,
  done_at     timestamptz,
  edited_at   timestamptz,
  -- pinned to a moment: seconds into the timeline, and a spot on the picture (0..1)
  at          double precision,
  x           double precision,
  y           double precision,
  -- who an audio comment is for, by name
  assignees   text[] not null default '{}',
  -- a reply: the note it answers; goes with it
  parent_id   uuid references public.comments(id) on delete cascade
);
-- a project made before the voice-over column existed
alter table public.comments add column if not exists kind text not null default 'comment';
alter table public.comments add column if not exists topic text not null default 'visual';
alter table public.comments add column if not exists edited_at timestamptz;
alter table public.comments add column if not exists at double precision;
alter table public.comments add column if not exists x double precision;
alter table public.comments add column if not exists y double precision;
alter table public.comments add column if not exists assignees text[] not null default '{}';
alter table public.comments add column if not exists parent_id uuid references public.comments(id) on delete cascade;
create index if not exists comments_release_state on public.comments (release_id, state_key);
create index if not exists comments_slide on public.comments (slide_id);

-- No sign-in: the site is shared by its link. Anyone with the anon key may read, add
-- releases and comments, tick comments done, and edit or delete a comment.
alter table public.releases enable row level security;
alter table public.comments enable row level security;

drop policy if exists "releases read"   on public.releases;
drop policy if exists "releases insert" on public.releases;
drop policy if exists "releases update" on public.releases;
create policy "releases read"   on public.releases for select using (true);
create policy "releases insert" on public.releases for insert with check (true);
create policy "releases update" on public.releases for update using (true) with check (true);

drop policy if exists "comments read"   on public.comments;
drop policy if exists "comments insert" on public.comments;
drop policy if exists "comments update" on public.comments;
drop policy if exists "comments delete" on public.comments;
create policy "comments read"   on public.comments for select using (true);
create policy "comments insert" on public.comments for insert with check (true);
create policy "comments update" on public.comments for update using (true) with check (true);
create policy "comments delete" on public.comments for delete using (true);

-- Live updates in every open tab. Harmless if the table is already published.
do $$
begin
  alter publication supabase_realtime add table public.comments;
exception when duplicate_object then null;
end $$;
do $$
begin
  alter publication supabase_realtime add table public.releases;
exception when duplicate_object then null;
end $$;

-- The bucket the films and thumbnails go in, readable by anyone with the link.
-- file_size_limit null means the project's own limit applies: 50 MB on the free plan,
-- raised under Storage > Settings on Pro. A film over that goes to any other host and
-- its URL into the release instead.
insert into storage.buckets (id, name, public)
values ('releases', 'releases', true)
on conflict (id) do update set public = true;

drop policy if exists "releases bucket read"   on storage.objects;
drop policy if exists "releases bucket insert" on storage.objects;
drop policy if exists "releases bucket update" on storage.objects;
create policy "releases bucket read"   on storage.objects for select using (bucket_id = 'releases');
create policy "releases bucket insert" on storage.objects for insert with check (bucket_id = 'releases');
create policy "releases bucket update" on storage.objects for update using (bucket_id = 'releases') with check (bucket_id = 'releases');
