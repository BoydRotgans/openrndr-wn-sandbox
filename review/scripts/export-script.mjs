// Erik's autocue, from the show into the rehearsal page, so the text edited there is the text he reads:
//
//     pnpm export-script                  # reads ../show-subtitles-erik.json, writes public/releases/practice/script.json
//     pnpm export-script --from <file>
//
// One line a state, keyed as the page keys its clicks (`slide-LETTER`), with who reads it where it is not Erik and
// the stage directions beside it. The page lays its own edits over these (src/practice/script.ts), so running this
// again after the show's text has moved shows the new text, and an edit made against the old one says so.
import fs from 'node:fs'
import path from 'node:path'

const here = path.dirname(new URL(import.meta.url).pathname)
const i = process.argv.indexOf('--from')
const from = i > 0 ? process.argv[i + 1] : path.join(here, '..', '..', 'show-subtitles-erik.json')
const out = path.join(here, '..', 'public', 'releases', 'practice', 'script.json')

const track = JSON.parse(fs.readFileSync(from, 'utf8'))
const letters = (o) => (typeof o === 'string' || Array.isArray(o) ? { A: o } : o || {})
const text = (v) => (Array.isArray(v) ? v.join(' ') : String(v ?? '')).trim()

const lines = {}, speakers = {}, directions = {}
for (const [id, states] of Object.entries(track.subtitles || {}))
  for (const [k, v] of Object.entries(letters(states))) if (text(v)) lines[`${id}-${k}`] = text(v)
for (const [id, states] of Object.entries(track.speakers || {}))
  for (const [k, v] of Object.entries(letters(states))) if (text(v)) speakers[`${id}-${k}`] = text(v)
for (const [id, states] of Object.entries(track.directions || {}))
  for (const [k, v] of Object.entries(letters(states))) {
    const list = (Array.isArray(v) ? v : [v]).map(text).filter(Boolean)
    if (list.length) directions[`${id}-${k}`] = list
  }

const script = {
  about: 'Erik\'s autocue as the show has it, for the rehearsal page to read and edit: written by review/scripts/export-script.mjs from show-subtitles-erik.json. Edits made on the page are kept apart, in Supabase, and laid over this.',
  exported: new Date().toISOString(),
  track: 'erik',
  label: 'Erik autocue',
  speaker: track.speaker || 'Erik Koremans',
  lines, speakers, directions,
}
fs.mkdirSync(path.dirname(out), { recursive: true })
fs.writeFileSync(out, JSON.stringify(script, null, 1) + '\n')
console.log(`wrote ${path.relative(process.cwd(), out)}: ${Object.keys(lines).length} lines, ${Object.keys(speakers).length} read by someone else, ${Object.keys(directions).length} with a direction`)
