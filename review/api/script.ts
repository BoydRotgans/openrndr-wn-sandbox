import { roleOf } from '../server/gate.js'

/**
 * The rehearsal page's edits to the autocue text, read and written on the server, as the speaker notes are
 * (api/notes.ts): the page carries no database key, and only the client's or the team's cookie gets through.
 *
 * **They ride in the notes' own table**, `speaker_notes`, under keys of their own (`script:<slide-LETTER>`), with
 * the edited text and the text it was edited from in the body as JSON — so the page needed no new table, which
 * would have meant SQL run by hand in the dashboard. The notes route leaves those keys out.
 *
 *     GET  /api/script   every edit: [{ key, slide, text, original, updated_at }]
 *     POST /api/script   the same shape, upserted on the key
 */
const SUPABASE = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || '').replace(/\/$/, '')
const KEY = process.env.VITE_SUPABASE_ANON_KEY || ''
const headers = { apikey: KEY, Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' }
const PREFIX = 'script:'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })

interface Row { state_key: string; slide_id: string; body: string; updated_at: string }

export async function GET(request: Request) {
  if (!(await roleOf(request))) return json({ error: 'private' }, 401)
  const r = await fetch(`${SUPABASE}/rest/v1/speaker_notes?select=state_key,slide_id,body,updated_at&state_key=like.${encodeURIComponent(PREFIX)}*`, { headers })
  if (!r.ok) return json({ error: `supabase ${r.status}` }, 502)
  const rows = (await r.json()) as Row[]
  return json(rows.flatMap((row) => {
    try {
      const b = JSON.parse(row.body) as { text?: unknown; original?: unknown }
      if (typeof b.text !== 'string') return []
      return [{ key: row.state_key.slice(PREFIX.length), slide: row.slide_id, text: b.text, original: typeof b.original === 'string' ? b.original : '', updated_at: row.updated_at }]
    } catch {
      return []
    }
  }))
}

export async function POST(request: Request) {
  if (!(await roleOf(request))) return json({ error: 'private' }, 401)
  let rows: unknown
  try {
    rows = await request.json()
  } catch {
    return json({ error: 'not json' }, 400)
  }
  const clean = Array.isArray(rows)
    ? rows.filter((r) =>
        r && typeof r.key === 'string' && r.key.length > 0 && r.key.length <= 200 &&
        typeof r.slide === 'string' && r.slide.length <= 200 &&
        typeof r.text === 'string' && r.text.length <= 20000 &&
        typeof r.original === 'string' && r.original.length <= 20000 &&
        typeof r.updated_at === 'string' && !Number.isNaN(Date.parse(r.updated_at)))
      .map((r) => ({
        state_key: PREFIX + r.key,
        slide_id: r.slide,
        body: JSON.stringify({ text: r.text, original: r.original }),
        updated_at: r.updated_at,
      }))
    : []
  if (!Array.isArray(rows) || clean.length !== rows.length || clean.length > 500) return json({ error: 'bad rows' }, 400)
  const r = await fetch(`${SUPABASE}/rest/v1/speaker_notes?on_conflict=state_key`, {
    method: 'POST',
    headers: { ...headers, Prefer: 'resolution=merge-duplicates,return=minimal' },
    body: JSON.stringify(clean),
  })
  return r.ok ? json({ saved: clean.length }) : json({ error: `supabase ${r.status}` }, 502)
}
