/**
 * The client's speaker notes: one text per click, keyed by the state's `slide-LETTER` so a note stays
 * with its click from one film to the next.
 *
 * Every keystroke is kept in this browser at once and sent a moment after typing stops to `/api/notes`
 * (api/notes.ts), which keeps them in Supabase's `speaker_notes` — so the notes are the same on any device
 * that opens the link, and the page holds no database key of its own. A note that cannot be saved —
 * offline, or under `vite`, which serves no /api — stays marked in the browser and is sent on the next
 * load; nothing typed is lost to a failed save.
 */
export type SaveState = 'idle' | 'saving' | 'saved' | 'local'

interface Entry {
  body: string
  slide: string
  updatedAt: string
  /** Written here and not yet confirmed by Supabase. */
  dirty?: boolean
}

interface Row {
  state_key: string
  slide_id: string
  body: string
  updated_at: string
}

const CACHE = 'wn-practice.notes.v1'
const DEBOUNCE = 700
const RETRY = 15000

const API = `${import.meta.env.BASE_URL.replace(/\/$/, '')}/api/notes`

class Remote {
  async list(): Promise<Row[]> {
    const r = await fetch(API, { credentials: 'same-origin', cache: 'no-store' })
    if (!r.ok || !r.headers.get('content-type')?.includes('json')) throw new Error(`${r.status}`)
    return r.json()
  }
  async upsert(rows: Row[], keepalive = false) {
    const r = await fetch(API, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(rows),
      keepalive,
    })
    if (!r.ok) throw new Error(`${r.status}`)
  }
}

export class Notes {
  private entries: Record<string, Entry> = read()
  private listeners = new Set<() => void>()
  private timer = 0
  private remote: Remote | null = new Remote()
  state: SaveState = 'idle'

  subscribe(f: () => void) {
    this.listeners.add(f)
    return () => { this.listeners.delete(f) }
  }

  get(key: string) {
    return this.entries[key]?.body ?? ''
  }

  has(key: string) {
    return !!this.entries[key]?.body.trim()
  }

  set(key: string, slide: string, body: string) {
    this.entries[key] = { body, slide, updatedAt: new Date().toISOString(), dirty: true }
    write(this.entries)
    this.state = this.remote ? 'saving' : 'local'
    this.emit()
    clearTimeout(this.timer)
    this.timer = window.setTimeout(() => this.flush(), DEBOUNCE)
  }

  /** Takes Supabase's copy of every note, keeping a local edit that is newer, then sends what is left. */
  async load() {
    if (!this.remote) return
    try {
      const rows = await this.remote.list()
      for (const row of rows) {
        const mine = this.entries[row.state_key]
        // compared as dates: Supabase writes +00:00 where the browser writes Z
        if (mine?.dirty && Date.parse(mine.updatedAt) > Date.parse(row.updated_at)) continue
        this.entries[row.state_key] = { body: row.body, slide: row.slide_id, updatedAt: row.updated_at }
      }
      write(this.entries)
      this.emit()
      await this.flush()
    } catch {
      this.setState('local')
    }
  }

  /** Sends every note not yet confirmed; `keepalive` lets it finish as the page closes. */
  async flush(keepalive = false) {
    clearTimeout(this.timer)
    if (!this.remote) return
    const pending = Object.entries(this.entries).filter(([, e]) => e.dirty)
    if (!pending.length) {
      if (this.state === 'saving') this.setState('saved')
      return
    }
    const sent = pending.map(([k, e]) => ({ key: k, at: e.updatedAt }))
    this.setState('saving')
    try {
      await this.remote.upsert(pending.map(([k, e]) => ({ state_key: k, slide_id: e.slide, body: e.body, updated_at: e.updatedAt })), keepalive)
      // a note typed into while the save was on its way is still dirty
      for (const { key, at } of sent) if (this.entries[key]?.updatedAt === at) delete this.entries[key].dirty
      write(this.entries)
      this.setState(Object.values(this.entries).some((e) => e.dirty) ? 'saving' : 'saved')
    } catch {
      this.setState('local')
      this.timer = window.setTimeout(() => this.flush(), RETRY)
    }
  }

  /** Every note with text, keyed by state. */
  written(): Record<string, string> {
    const out: Record<string, string> = {}
    for (const [k, e] of Object.entries(this.entries)) if (e.body.trim()) out[k] = e.body
    return out
  }

  private setState(s: SaveState) {
    if (this.state === s) return
    this.state = s
    this.emit()
  }

  private emit() {
    this.listeners.forEach((f) => f())
  }
}

function read(): Record<string, Entry> {
  try {
    return JSON.parse(localStorage.getItem(CACHE) || '{}')
  } catch {
    return {}
  }
}

function write(entries: Record<string, Entry>) {
  try {
    localStorage.setItem(CACHE, JSON.stringify(entries))
  } catch {
    /* private window: the notes still go to Supabase */
  }
}
