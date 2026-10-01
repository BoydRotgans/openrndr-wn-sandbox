// The gains set in the audio timeline review, from Supabase into the show's `show-gains.json`:
//
//     pnpm pull-gains                  # writes ../show-gains.json, beside show-order.json
//     pnpm pull-gains --out <file>
//
// The same file the page's "export show-gains.json" writes — both go through src/lib/gains.ts —
// for a release made from the terminal. Reads VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY from the
// environment or review/.env: the gains are read with the key the site itself reads them with.
// Plain Node runs it: it strips the types itself, and gains.ts imports nothing but types.
import fs from 'node:fs'
import path from 'node:path'
import { gainsJson } from '../src/lib/gains.ts'
import type { Comment } from '../src/lib/types.ts'

const here = path.dirname(new URL(import.meta.url).pathname)
const envFile = path.join(here, '..', '.env')
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/)
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '')
  }
}
const url = (process.env.VITE_SUPABASE_URL || process.env.SUPABASE_URL || '').replace(/\/$/, '')
const key = process.env.VITE_SUPABASE_ANON_KEY || process.env.SUPABASE_SERVICE_KEY || ''
if (!url || !key) {
  console.error('VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY are needed (review/.env or the environment)')
  process.exit(1)
}
const i = process.argv.indexOf('--out')
const out = i > 0 ? process.argv[i + 1] : path.join(here, '..', '..', 'show-gains.json')

const r = await fetch(`${url}/rest/v1/comments?select=*&kind=eq.audio_gain&order=created_at`, {
  headers: { apikey: key, Authorization: `Bearer ${key}` },
})
if (!r.ok) {
  console.error(`could not read the gains: ${r.status} ${await r.text()}`)
  process.exit(1)
}
const rows = (await r.json()) as Record<string, unknown>[]
const comments = rows.map((c) => ({
  kind: c.kind, body: c.body, clip: c.clip, clipFile: c.clip_file, author: c.author, createdAt: c.created_at,
})) as unknown as Comment[]
const text = gainsJson(comments)
fs.writeFileSync(out, text)
const design = JSON.parse(text).design as Record<string, { db: number }>
const off = Object.values(design).filter((g) => Math.abs(g.db) >= 0.05).length
console.log(`gains: ${Object.keys(design).length} sound design states reviewed, ${off} off 0 dB — ${out}`)
