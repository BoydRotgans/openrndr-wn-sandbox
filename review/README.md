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

### Audio timeline review

The second tab in the header. Every sound the film played is a clip on a timeline of its own —
**A1 Voice**, **A2 Sound design**, **A3 Music** under the film's states as **V1** — placed where it
played, with its file name on it and its own waveform in it, and a small player over it.

- Click a clip and the film plays that sound's part and stops where it stops; **loop** holds it,
  **solo** plays the file on its own over the picture.
- Every clip has a status — **undone**, **needs work**, **done** — and a thread, with notes for
  Valentina or Jurre (they show up under Tasks and in the review's Audio tab too).
- A state with no cue, or with a voice-over line and no voice, is drawn as a red hatched gap across
  it. A gap can be marked done when the state is meant to stay silent.
- A silent head is hatched at the start of a clip, a fade-out is the diagonal at its end, and a file
  that is silent end to end is edged red.
- Every sound-design clip has a **gain**, in dB against the file as delivered: a fader, `−1`/`+1`,
  a number, or `[` `]` on the keyboard. The panel shows the loudness (LUFS) and peak it gives, and
  how far under the voice line on the same state the cue sits. The waveform is drawn at the gain, a
  clip that would clip is flagged, and a gain the film was not made at yet is marked amber.
- **Listen**: *film* is the film's own soundtrack; *remix* rebuilds it here from the sound files at
  the gains being set, so a change is heard in place; *solo* is the selected sound alone (`M`, `S`).
- The gains go into the next release through `show-gains.json` at the project root: **export
  show-gains.json** in the panel or the overview, or `pnpm pull-gains`, which writes it there from
  Supabase. The show applies it as each cue fires (`SLIDES_GAINS`), so the next film is mixed with
  it, and that release's timeline shows the gains as in the film.
- The wheel scrolls sideways; ⌘/ctrl + wheel (or a pinch) zooms about the pointer, `\` fits the
  film. `←` `→` step the sounds on a track, `↑` `↓` change track, `1` `2` `3` set the status,
  `S` solo, `L` loop, space plays. `#audio/<release>/<slot>` links to a sound.

## Run it locally

```
cd review
pnpm install
pnpm dev            # http://localhost:5180, and the practice page at /practice
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

   Before filming, `pnpm pull-gains` in `review/` brings the gains set in the audio timeline review
   into `show-gains.json`; commit it with the release.

   The build also writes the audio timeline: `audio.json` (every cue in the film's `.cues` log as a clip)
   and `sounds/` (a playable copy of every file, ~35 MB, built and not committed). For a release
   folder that has none, `python3 tools/release_audio.py review/public/releases/<slug>` — but only
   while `data/sounds` still holds what that film played, since the clips are measured off the files.

## Share it: Supabase and Vercel

Comments are shared through a Supabase project — two tables and one storage bucket.

1. Make a project at supabase.com. In **SQL editor**, run `supabase/schema.sql` once. It is safe
   to run again, and must be after a change to it: the audio timeline's `clip` and `clip_file`
   columns came in that way.
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

   **The links are private, and the gate is on the server.** `middleware.ts` runs on Vercel before
   every request. `?k=<key>` is checked there against the keys' hashes (`VITE_ACCESS_KEY_SHA256`,
   `VITE_PRACTICE_KEY_SHA256`) and kept as an HttpOnly cookie holding the key itself, so the hashes,
   which are also in the page's code, cannot be turned into a cookie. The team's key reaches
   everything. The client's key reaches the rehearsal page, its code (`assets/r/`), its film
   (`releases/practice/`, `releases/practice.json`) and `/api/notes` — and nothing else: not the
   review, not its code (`assets/a/`), not a review release, not the release list. Without a cookie a
   page request gets a small unlock page, which tries a key the browser kept from before the gate once
   and otherwise says the page is private; anything else is a 404. The rehearsal page's notes go
   through `api/notes.ts`, so its code carries no database key. With a hash missing on the server,
   everything is shut. The keys are on the project as `VITE_ACCESS_KEY` (read by nothing any more) and
   `PRACTICE_KEY`, read back with `npx vercel env pull`.

   Two traps met putting it up. **On Vercel the middleware runs as plain Node ES modules**, so a
   relative import needs its `.js` (`./server/gate.js`) — without it every request answered 500
   (`MIDDLEWARE_INVOCATION_FAILED`) until it was rolled back. So try a change to the gate on a preview
   first: `npx vercel deploy` (no `--prod`) with the keys passed as `--env`/`--build-env`, then
   `npx vercel curl <path> --deployment <url> -- -L -b "wn_admin=…"` to get past the preview's own
   login. **And after a `vercel rollback` a new `--prod` deploy is not put live** until `vercel promote`.

   With the release served by the site itself, register it in Supabase without uploading:
   `pnpm publish-release public/releases/2026-09-21 --static`.

### Moving to another Supabase account

Nothing is tied to a project: run `supabase/schema.sql` in the new one, swap the two `VITE_*`
keys (in `.env` and in Vercel), and publish the releases again with `pnpm publish-release`
against the new `SUPABASE_URL` / `SUPABASE_SERVICE_KEY`. To carry the comments over, export the
old project's `comments`, `releases` and `speaker_notes` tables as CSV from its Table editor and import them into
the new one the same way (Table editor > Insert > Import data from CSV); the ids are UUIDs and
travel as they are. The site's own **Export CSV** is the human-readable copy.

## The practice page

`/practice` is the show for the client to rehearse on, on its own link: the film large, a click at
a time, with room for their own speaker notes. Nothing of the review is on it, and **no voice-over
is ever heard there and no subtitles are burned into its film**. Since 2 October the subtitles are on
it as text: above the speaker notes, the line said over the click on screen (the extended track, the
standard one where a click has no extended line), only where there is one. **It comes in word by word
as the click plays**, at the show's own subtitle pace (15 characters a second with the last word at 80% of
the card, about three words a second), in step with the film: paused, it holds; standing still, the line
is whole; every word is laid out from the start so nothing reflows. On by default; the CC button in the
head or S hides it, remembered per browser.

- **Volgende** / **Vorige** (→ ←, space, or a presenter clicker's Page Down / Page Up — those work
  even while typing a note) play the next or previous click from its start, and the film then holds
  on that click's last frame until the next one, as the show waits for the presenter. The Next
  button glows once the click has finished. **Opnieuw** (R) plays it again.
- **Alle dia's** (O, or the clicker's play button, which sends F5) lists every slide under its
  chapter or moment, with a chip a click; any of them jumps there. The strip under the film is the
  evening as chapters and moments, a click away from the start of each.
- **Speaker notes** are one text per click, saved as they are typed — to the browser at once and to
  Supabase's `speaker_notes` table a moment later, so they are the same on any device with the link.
  A note that cannot be saved online stays in the browser, says so, and is sent on the next load.
  *Hierna* shows the next click and its note; **Notities downloaden** writes every note, in the
  running order, to a text file.
- **Delen** in the head shows the page's secret link with a copy button. It is always the practice
  key's link, even for a team member who opened the page on the review key: for them the page opens
  `VITE_PRACTICE_KEY_SEALED`, the practice key sealed with the review key
  (`scripts/seal-practice-key.mjs`), so no readable key is ever in the page.
- Dutch by default, English with the switch in the head; the address carries the click
  (`/practice#de-cijfers-C`) as it is clicked through, but **the page always opens on the opening scene**,
  whatever the address or the last visit said (2 October): a rehearsal starts at the top.

**What it plays is the newest cut in `public/releases/practice.json`** — the latest `created`, and of
two on one day the one listed last. There is no choosing between versions on the page; its head
says which version it is ("Versie 29 september 2026").

```json
{ "cuts": [ { "slug": "2026-09-28", "created": "2026-09-28", "folder": "2026-09-28", "audio": "novoice" } ] }
```

`folder` is under `public/releases/`. `audio` is the only sound the page may play: the key of one of
the manifest's extra soundtracks (the no-voice mix, played over the muted film), or `film` for a cut
whose own soundtrack has no voice. Anything else and the page is silent. The voice-over lines in a
manifest are never played; they are read as text for the subtitle above the notes (`Cut.lines`), so a
practice cut is built with its subtitles in the manifest.

**A practice cut is a film of its own, because the review films carry the subtitles in the
picture.** Until the first one exists the page plays the 28 September review film with its no-voice
track, subtitles and all. With the next release, film a second run with the subtitles off and the
voice on — the voice is what times the states to the length of the talk — and mix the voice out:

```
SLIDES_SUBTITLE_MODE=false SLIDES_SUBTITLE_TRACK=extended SLIDES_VOICE_ON=true SLIDES_MIX_VOICE=1.0 \
    SLIDES_MIX_DESIGN=1.0 SLIDES_MIX_MUSIC=1.0 SLIDES_CUES=auto SLIDES_RECORD=true SLIDES_FPS=30 \
    SLIDES_MUTED=true SLIDES_NAMEPLATE=false SLIDES_VIDEO=video/wn-experience_<date>-clean.mp4 \
    ./gradlew run -Popenrndr.application=SlideshowKt
SLIDES_VIDEO=video/wn-experience_<date>-clean.mp4 SLIDES_MIX_LAYERS=voice=0 SLIDES_MIX_NAME=novoice \
    ./gradlew run -Popenrndr.application=MixSoundtrackKt
python3 tools/release_build.py --video video/wn-experience_<date>-clean-novoice.mp4 \
    --states video/wn-experience_<date>-clean.states --name "Rehearsal <date>" --slug <date> \
    --created <date> --out review/public/releases/practice \
    --no-waveform --no-audio-timeline --width 2560 --crf 33
```

With the same settings as the review film but for the subtitles, the auto cues come out the same —
checked on 29 September, the two cue lists identical to the hundredth of a second — so the clean
film lands state for state on the review film's frames, and `-clean-mixed.mp4` beside it is the
subtitle-free film *with* the voice.

then add `{ "slug": "<date>", "created": "<date>", "folder": "practice/<date>", "audio": "film" }` to
`practice.json` and deploy; being the newest, it is the one played. 2560 wide rather than the review's
1920, since here the film is the page — at crf 33, because Vercel refuses any single file over 100 MB
and a half-hour film at crf 30 is 133 MB. Check the size before deploying: on 30 September both films
came out just over it (the review film 100.5 MB at crf 30, the rehearsal 101.7 MB at crf 33), and one
step up, crf 31 and crf 34, brought them to 91 MB each.

## What is where

- `src/App.tsx` — the page: which release, which state is under the playhead, what is unread,
  and which of the two pages is up.
- `src/components/AudioReview.tsx`, `AudioTimeline.tsx`, `ClipPanel.tsx`, `src/lib/audio.ts` — the
  audio timeline review: the page, the timeline, the panel for one sound, and the clips as slots.
- `src/components/` — `Player` (the film, looping on a state), `Minimap` (the strip),
  `Thread` (the chat), `Header` (release, bell, name, export), `Dialogs` (name, new release).
- `src/lib/localStore.ts`, `src/lib/supabaseStore.ts` — the two backends behind one `Store`.
- `supabase/schema.sql` — the tables, policies and bucket.
- `practice.html`, `src/practice/` — the practice page, a second entry that loads none of the
  review's code: `Practice` (the page), `Film` (a click at a time), `Overview` (every slide), `deck`
  (the cut and the running order as chapters and slides), `notes` (the speaker notes), `copy`
  (its words, Dutch and English).
- `middleware.ts`, `server/gate.ts` — the gate on Vercel: who a request is from, and what each key reaches.
- `api/notes.ts` — the rehearsal page's speaker notes, read and written on the server.
- `src/lib/access.ts` — both links checked again in the page, and the rehearsal link for Delen.
- `scripts/publish-release.mjs` — a release folder into Supabase, from the terminal.
- `../tools/release_build.py` — a filmed run into a release folder.
- `../tools/release_audio.py` — a film's cue log into the release's `audio.json` and `sounds/`.
- `src/lib/remix.ts` — the soundtrack rebuilt from its files in the browser, at the reviewed gains.
- `src/lib/gains.ts`, `scripts/pull-gains.ts` — `show-gains.json`, from the page and from the terminal.
