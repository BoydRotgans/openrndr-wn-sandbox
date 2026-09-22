# WN review

The filmed show, cut into its slides, with a comment thread on every state — so the team can
watch a release together and say what needs changing, per slide, with a name on every note.

- Click a slide in the strip along the bottom and the film plays that state; **loop** holds it.
- The thread on the right is the chat for the state on screen. First post asks your name once.
- The bell in the header lists the states with comments you have not seen; the strip marks them
  with a red dot and counts the open comments on each.
- Every comment has a **done** tick, so feedback can be checked off one by one against the next
  release. The thread also folds in open comments on the same slide from earlier releases.
- **Export CSV** (top right) downloads every comment of every release.
- **+ release** adds a new film; `←` `→` step the states, space plays, `L` loops.
- The address carries the state (`#2026-09-21/the-catalogue-city-B`), so a link lands on it.

## Run it locally

```
cd review
pnpm install
pnpm dev            # http://localhost:5180
```

With no Supabase keys the site runs in **local mode**: releases are read off
`public/releases/index.json` and comments live in this browser only. That is enough to look at a
release and try the UI; sharing needs Supabase (below).

## Build a release from a filmed run

A release is a folder under `public/releases/<slug>/`: a `manifest.json` with every state and its
start and end in the film, a thumbnail per state, and the film scaled for the web.

1. Film the run. Since the state log went in, a filmed run writes `video/<name>.states` beside
   its `.cues` — which state was on screen from which frame. For a film made before that,
   derive it from the deck with the same settings the film used:

   ```
   SLIDES_SUBTITLE_MODE=true SLIDES_VIDEO=video/wn-experience-subtitles-full.mp4 \
       ./gradlew run -Popenrndr.application=ReleaseStatesKt
   ```

   It prints its own length against the film's; they should match to a second.

2. Cut the release (needs ffmpeg):

   ```
   python3 tools/release_build.py --video video/wn-experience-subtitles-full-mixed.mp4 \
       --name "Release 21 September" --slug 2026-09-21 --created 2026-09-21
   ```

   The 22-minute film comes out around 60 MB at 1920 wide, `--crf 30`. Reload the site and the
   release is listed.

## Share it: Supabase and Vercel

Comments are shared through a Supabase project — two tables and one storage bucket.

1. Make a project at supabase.com. In **SQL editor**, run `supabase/schema.sql` once.
2. Copy `.env.example` to `.env` and fill in the project's URL and **anon** key
   (`VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`). Restart `pnpm dev`; the header badge reads
   `SUPABASE`.
3. Publish the release folder to it from the terminal (the film is too big for a browser upload
   on most plans). Put the project's **service role** key in `.env` as `SUPABASE_SERVICE_KEY` —
   only this script reads it, the site never does:

   ```
   pnpm publish-release public/releases/2026-09-21 --write-manifest
   ```

   It uploads the thumbnails and the film to the `releases` bucket and registers the release.
   The free plan caps a file at 50 MB; if the film is over it, host it anywhere and pass
   `--video-url https://…/video.mp4` instead, or raise the limit under Storage > Settings on Pro.
   `--write-manifest` also points the folder's manifest at the uploaded film, so local mode plays
   the same file.

4. Deploy on Vercel. The site is deployed from this folder with the CLI, as project
   `wn-review` under the `boydrndrstudios-projects` scope (`.vercel/project.json` holds the
   link): `npx vercel deploy --prod --yes`. `.vercelignore` rather than `.gitignore` decides
   what is uploaded, so the release folder goes up with its video and the site serves it
   itself. Environment variables live on the project (`npx vercel env ls`); a Supabase project
   is wired in with `npx vercel env add VITE_SUPABASE_URL production --type config --value …
   --yes` and the anon key the same way, then a redeploy.

   **The link is private.** `VITE_ACCESS_KEY` is set on the project, and the site only opens
   through `https://wn-review-beta.vercel.app/?k=<key>`; the browser remembers the key after
   the first visit and the address is cleaned. Without it the page says the review is
   private. That keeps the *link* private rather than the data secret — the key is in the
   page's code, as the anon key is — so it is a shared-link site, not a login. The key is read
   back with `npx vercel env pull`.

   With the release served by the site itself, register it in Supabase without uploading:
   `pnpm publish-release public/releases/2026-09-21 --static`.

### Moving to another Supabase account

Nothing is tied to a project: run `supabase/schema.sql` in the new one, swap the two `VITE_*`
keys (in `.env` and in Vercel), and publish the releases again with `pnpm publish-release`
against the new `SUPABASE_URL` / `SUPABASE_SERVICE_KEY`. To carry the comments over, export the
old project's `comments` and `releases` tables as CSV from its Table editor and import them into
the new one the same way (Table editor > Insert > Import data from CSV); the ids are UUIDs and
travel as they are. The site's own **Export CSV** is the human-readable copy.

## What is where

- `src/App.tsx` — the page: which release, which state is under the playhead, what is unread.
- `src/components/` — `Player` (the film, looping on a state), `Minimap` (the strip),
  `Thread` (the chat), `Header` (release, bell, name, export), `Dialogs` (name, new release).
- `src/lib/localStore.ts`, `src/lib/supabaseStore.ts` — the two backends behind one `Store`.
- `supabase/schema.sql` — the tables, policies and bucket.
- `scripts/publish-release.mjs` — a release folder into Supabase, from the terminal.
- `../tools/release_build.py` — a filmed run into a release folder.
