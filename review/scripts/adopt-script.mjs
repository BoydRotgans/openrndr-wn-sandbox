// The rehearsal page's edits to Erik's autocue, taken into the show — the step after `pnpm pull-script`:
//
//     pnpm pull-script                  # ../show-script-edits.json: every click changed on the page
//     pnpm adopt-script                 # takes them in, then writes the page's script.json again
//     pnpm adopt-script --only <key,…>  # just those clicks
//
// Each edit's text goes into ../show-subtitles-erik.json, the track the show and the autocue read, and into
// ../show-autocue-additions.json, so a new import of the production's Word file (tools/autocue_docx.py) keeps it.
// Line breaks are kept: the autocue sets them. ../show-script-adopted.json records what was taken in, when, and
// which version of the page's edit it was, and export-script.mjs hands that to the page, which marks the click
// as taken in rather than as changed. An edit made on the page after that is a new change again.
//
// **An edit may be taken in other than as typed.** Give it `adopt` in show-script-edits.json — the text to take in
// instead — and `note`, which the page shows beside it: a remark about the wall typed into the text, say, is not
// for the speaker to read. An edit whose original no longer matches the track is not taken in (the track moved
// on since the page edited from it); --force takes it in all the same.
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const here = path.dirname(new URL(import.meta.url).pathname)
const root = path.join(here, '..', '..')
const arg = (name) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : undefined }
const from = arg('--from') ?? path.join(root, 'show-script-edits.json')
const only = arg('--only')?.split(',').map((s) => s.trim()).filter(Boolean)
const force = process.argv.includes('--force')
const files = {
  track: path.join(root, 'show-subtitles-erik.json'),
  additions: path.join(root, 'show-autocue-additions.json'),
  record: path.join(root, 'show-script-adopted.json'),
}

const read = (f) => JSON.parse(fs.readFileSync(f, 'utf8'))
const write = (f, o) => fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n')
const flat = (s) => (Array.isArray(s) ? s.join(' ') : String(s ?? '')).replace(/\s+/g, ' ').trim()
/** The text as the autocue sets it: single spaces, each line trimmed, no run of blank lines, nothing at either end. */
const clean = (s) => String(s ?? '').replace(/\r/g, '').split('\n').map((l) => l.replace(/[ \t]+/g, ' ').trim()).join('\n').replace(/\n{3,}/g, '\n\n').trim()

const edits = read(from).edits.filter((e) => !only || only.includes(e.key))
const track = read(files.track)
const additions = read(files.additions)
const record = fs.existsSync(files.record) ? read(files.record) : { adopted: {} }
const today = new Date().toISOString().slice(0, 10)
const at = new Date().toISOString()

const taken = [], refused = []
for (const e of edits) {
  const cut = e.key.lastIndexOf('-')
  const id = e.key.slice(0, cut), letter = e.key.slice(cut + 1)
  const text = clean(e.adopt ?? e.text)
  const now = track.subtitles?.[id]?.[letter] ?? ''
  // A later version of an edit taken in before: the track moved only because of that, so it is no conflict, and
  // what the click said before is still what it said before the first version.
  const before = record.adopted[e.key]
  const continues = before && flat(now) === flat(before.text) && [before.from, before.text].some((t) => flat(t) === flat(e.original))
  if (flat(now) !== flat(e.original) && flat(now) !== flat(text) && !continues && !force) {
    refused.push(`${e.key}: the track says “${flat(now).slice(0, 70)}…”, not the text the page edited from`)
    continue
  }
  track.subtitles[id] ??= {}
  if (text) track.subtitles[id][letter] = text
  else delete track.subtitles[id][letter]
  const lines = text.split('\n')
  additions.states[e.key] = { ...additions.states[e.key], said: lines.length > 1 ? lines : text, adopted: today }
  record.adopted[e.key] = {
    at, edited: e.updated_at, slide: e.slide ?? id, from: continues ? before.from : e.original, text,
    ...(e.note ? { note: e.note } : {}),
  }
  taken.push(e)
}

const NOTE = ' Lines rewritten on the rehearsal page carry adopted, the day review/scripts/adopt-script.mjs took them in (show-script-adopted.json has what each said before).'
if (taken.length && !additions.note.includes(NOTE.trim())) additions.note += NOTE
record.about = 'The rehearsal page\'s edits to Erik\'s autocue that were taken into the show, written by review/scripts/adopt-script.mjs: for each click, when (at), which version of the page\'s edit (edited, its updated_at in Supabase), what it said before (from), what it says now (text) and, where it was taken in other than as typed, why (note). export-script.mjs hands it to the page, which marks those clicks as taken in.'
record.adopted = Object.fromEntries(Object.entries(record.adopted).sort(([a], [b]) => a.localeCompare(b)))
if (taken.length) {
  write(files.track, track)
  write(files.additions, additions)
  write(files.record, { about: record.about, adopted: record.adopted })
}
console.log(`took in ${taken.length} of ${edits.length} edit${edits.length === 1 ? '' : 's'}`)
for (const e of taken) console.log(`  ${e.key}${e.adopt !== undefined ? '  (not as typed: ' + (e.note ?? 'see show-script-adopted.json') + ')' : ''}`)
for (const r of refused) console.log(`  refused ${r}`)
if (taken.length) execFileSync(process.execPath, [path.join(here, 'export-script.mjs')], { stdio: 'inherit' })
if (refused.length) process.exitCode = 1
