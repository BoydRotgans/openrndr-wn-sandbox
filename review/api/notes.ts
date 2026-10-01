import { roleOf } from '../server/gate.js'

/**
 * The rehearsal page's speaker notes, read and written on the server, so the page carries no database
 * key: with the Supabase key in its code, whoever had the rehearsal link could read every review comment.
 * Only a request carrying the client's or the team's cookie gets through (the middleware checks it too).
 *
 *     GET  /api/notes   every note: [{ state_key, slide_id, body, updated_at }]
 *     POST /api/notes   the same shape, upserted on state_key
 */
const SUPABASE = (process.env.SUPABASE_URL || process.env.VITE_SUPABASE_URL || '').replace(/\/$/, '')
const KEY = process.env.VITE_SUPABASE_ANON_KEY || ''
const headers = { apikey: KEY, Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' }

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })

export async function GET(request: Request) {
  if (!(await roleOf(request))) return json({ error: 'private' }, 401)
  const r = await fetch(`${SUPABASE}/rest/v1/speaker_notes?select=state_key,slide_id,body,updated_at`, { headers })
  return r.ok ? json(await r.json()) : json({ error: `supabase ${r.status}` }, 502)
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
        r && typeof r.state_key === 'string' && r.state_key.length > 0 && r.state_key.length <= 200 &&
        typeof r.slide_id === 'string' && r.slide_id.length <= 200 &&
        typeof r.body === 'string' && r.body.length <= 20000 &&
        typeof r.updated_at === 'string' && !Number.isNaN(Date.parse(r.updated_at)))
      .map((r) => ({ state_key: r.state_key, slide_id: r.slide_id, body: r.body, updated_at: r.updated_at }))
    : []
  if (!Array.isArray(rows) || clean.length !== rows.length || clean.length > 500) return json({ error: 'bad rows' }, 400)
  const r = await fetch(`${SUPABASE}/rest/v1/speaker_notes?on_conflict=state_key`, {
    method: 'POST',
    headers: { ...headers, Prefer: 'resolution=merge-duplicates,return=minimal' },
    body: JSON.stringify(clean),
  })
  return r.ok ? json({ saved: clean.length }) : json({ error: `supabase ${r.status}` }, 502)
}
