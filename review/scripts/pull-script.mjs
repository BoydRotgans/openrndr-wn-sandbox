// The rehearsal page's edits to the autocue text, from Supabase into the project, to be taken into Erik's track:
//
//     pnpm pull-script                  # writes ../show-script-edits.json and lists every change
//     pnpm pull-script --out <file>
//
// Only the clicks whose text differs from what it was are written, each with the text it was edited from, the
// text it became and when — what the page's changes list shows. Reads VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY
// from the environment or review/.env, the key the site's own server reads them with (api/script.ts).
import fs from 'node:fs'
import path from 'node:path'

const here = path.dirname(new URL(import.meta.url).pathname)
const envFile = path.join(here, '..', '.env')
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '')
  }
}
const url = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || '').replace(/\/$/, '')
const key = process.env.VITE_SUPABASE_ANON_KEY || ''
if (!url || !key) {
  console.error('VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY are needed (review/.env or the environment)')
  process.exit(1)
}
const i = process.argv.indexOf('--out')
const out = i > 0 ? process.argv[i + 1] : path.join(here, '..', '..', 'show-script-edits.json')

const r = await fetch(`${url}/rest/v1/speaker_notes?select=state_key,slide_id,body,updated_at&state_key=like.script%3A*`, {
  headers: { apikey: key, Authorization: `Bearer ${key}` },
})
if (!r.ok) {
  console.error(`supabase ${r.status}: ${await r.text()}`)
  process.exit(1)
}
const flat = (s) => String(s ?? '').replace(/\s+/g, ' ').trim()
const edits = (await r.json()).flatMap((row) => {
  try {
    const b = JSON.parse(row.body)
    if (typeof b.text !== 'string' || flat(b.text) === flat(b.original)) return []
    return [{ key: row.state_key.slice('script:'.length), slide: row.slide_id, original: b.original ?? '', text: b.text, updated_at: row.updated_at }]
  } catch {
    return []
  }
}).sort((a, b) => a.key.localeCompare(b.key))

fs.writeFileSync(out, JSON.stringify({
  about: 'The rehearsal page\'s edits to Erik\'s autocue text, pulled by review/scripts/pull-script.mjs: each click whose text was changed there, with the text it was edited from (original) and the text it became (text). Not read by the show: take them into show-subtitles-erik.json and show-autocue-additions.json.',
  pulled: new Date().toISOString(),
  edits,
}, null, 2) + '\n')
console.log(`wrote ${path.relative(process.cwd(), out)}: ${edits.length} changed click${edits.length === 1 ? '' : 's'}`)
for (const e of edits) console.log(`\n${e.key}  (${e.updated_at.slice(0, 16).replace('T', ' ')})\n  was: ${flat(e.original) || '—'}\n  now: ${flat(e.text) || '—'}`)
