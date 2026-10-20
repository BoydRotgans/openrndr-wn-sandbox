/**
 * The autocue text, editable on the page: Erik's script as the show has it (`releases/practice/script.json`,
 * written by scripts/export-script.mjs), with the edits made here laid over it, one text a click, keyed by the
 * state's `slide-LETTER` as the notes are.
 *
 * **An edit keeps the text it was made from**, so what changed is always there to be shown — a click edited reads
 * as changed, with the original beside it, until it is set back — and stays readable if the show's own text moves on
 * after it: the page then says so rather than quietly comparing against the new one.
 *
 * Saved the way the notes are (notes.ts): every keystroke in this browser at once, then a moment after typing stops
 * to `/api/script` (api/script.ts), which keeps them in Supabase; a save that fails is kept here and sent again.
 */
import type { SaveState } from './notes'

export interface Edit {
  key: string
  slide: string
  text: string
  /** The text this click had when it was first edited here. */
  original: string
  updatedAt: string
  /** Written here and not yet confirmed by Supabase. */
  dirty?: boolean
}

export interface ScriptBase {
  exported: string
  label: string
  /** Who reads a line no `speakers` entry names. */
  speaker: string
  lines: Record<string, string>
  speakers: Record<string, string>
  directions: Record<string, string[]>
}

interface Row { key: string; slide: string; text: string; original: string; updated_at: string }

const CACHE = 'wn-practice.script.v1'
const DEBOUNCE = 700
const RETRY = 15000
const base = import.meta.env.BASE_URL.replace(/\/$/, '')
const API = `${base}/api/script`
const EMPTY: ScriptBase = { exported: '', label: '', speaker: '', lines: {}, speakers: {}, directions: {} }

/** The same text, give or take white space at its ends and in between. */
const same = (a: string, b: string) => a.replace(/\s+/g, ' ').trim() === b.replace(/\s+/g, ' ').trim()

export class Script {
  base: ScriptBase = EMPTY
  private edits: Record<string, Edit> = read()
  private listeners = new Set<() => void>()
  private timer = 0
  state: SaveState = 'idle'

  subscribe(f: () => void) {
    this.listeners.add(f)
    return () => { this.listeners.delete(f) }
  }

  /** The show's text and Supabase's edits; the page works with the edits kept here if either fails. */
  async load() {
    try {
      const r = await fetch(`${base}/releases/practice/script.json`, { cache: 'no-cache' })
      if (r.ok) this.base = { ...EMPTY, ...(await r.json()) }
    } catch { /* no script published: the page edits from nothing */ }
    this.emit()
    try {
      const r = await fetch(API, { credentials: 'same-origin', cache: 'no-store' })
      if (!r.ok || !r.headers.get('content-type')?.includes('json')) throw new Error(`${r.status}`)
      for (const row of (await r.json()) as Row[]) {
        const mine = this.edits[row.key]
        if (mine?.dirty && Date.parse(mine.updatedAt) > Date.parse(row.updated_at)) continue
        this.edits[row.key] = { key: row.key, slide: row.slide, text: row.text, original: row.original, updatedAt: row.updated_at }
      }
      write(this.edits)
      this.emit()
      await this.flush()
    } catch {
      if (Object.values(this.edits).some((e) => e.dirty)) this.setState('local')
    }
  }

  /** The text of a click: as edited here, else the show's. */
  text(key: string) {
    return this.edits[key]?.text ?? this.base.lines[key] ?? ''
  }

  /** What the click said before it was edited here: the show's text then. */
  original(key: string) {
    return this.edits[key]?.original ?? this.base.lines[key] ?? ''
  }

  /** Whether the click's text differs from what it said before it was edited. */
  changed(key: string) {
    const e = this.edits[key]
    return !!e && !same(e.text, e.original)
  }

  /** Whether the show's own text has moved on since this click was edited from it. */
  moved(key: string) {
    const e = this.edits[key]
    return !!e && this.changed(key) && !same(e.original, this.base.lines[key] ?? '')
  }

  edit(key: string): Edit | undefined {
    return this.edits[key]
  }

  /** Who reads this click: the one the show names, else the script's own speaker where there is something to say. */
  speaker(key: string) {
    return this.base.speakers[key] ?? (this.text(key).trim() ? this.base.speaker : '')
  }

  /** Whether that is someone other than the script's own speaker. */
  guest(key: string) {
    const s = this.base.speakers[key]
    return !!s && s !== this.base.speaker
  }

  directions(key: string) {
    return this.base.directions[key] ?? []
  }

  set(key: string, slide: string, text: string) {
    const original = this.original(key)
    this.edits[key] = { key, slide, text, original, updatedAt: new Date().toISOString(), dirty: true }
    write(this.edits)
    this.setState('saving')
    this.emit()
    clearTimeout(this.timer)
    this.timer = window.setTimeout(() => this.flush(), DEBOUNCE)
  }

  /** Back to what it said before: kept as an edit that changes nothing, so the setting back is saved too. */
  revert(key: string, slide: string) {
    this.set(key, slide, this.original(key))
  }

  /** Every click whose text differs from what it was, newest first. */
  changes(): Edit[] {
    return Object.values(this.edits).filter((e) => !same(e.text, e.original)).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
  }

  /** Sends every edit not yet confirmed; `keepalive` lets it finish as the page closes. */
  async flush(keepalive = false) {
    clearTimeout(this.timer)
    const pending = Object.values(this.edits).filter((e) => e.dirty)
    if (!pending.length) {
      if (this.state === 'saving') this.setState('saved')
      return
    }
    const sent = pending.map((e) => ({ key: e.key, at: e.updatedAt }))
    this.setState('saving')
    try {
      const r = await fetch(API, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(pending.map((e) => ({ key: e.key, slide: e.slide, text: e.text, original: e.original, updated_at: e.updatedAt }))),
        keepalive,
      })
      if (!r.ok) throw new Error(`${r.status}`)
      // a click typed into while the save was on its way is still dirty
      for (const { key, at } of sent) if (this.edits[key]?.updatedAt === at) delete this.edits[key].dirty
      write(this.edits)
      this.setState(Object.values(this.edits).some((e) => e.dirty) ? 'saving' : 'saved')
    } catch {
      this.setState('local')
      this.timer = window.setTimeout(() => this.flush(), RETRY)
    }
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

function read(): Record<string, Edit> {
  try {
    return JSON.parse(localStorage.getItem(CACHE) || '{}')
  } catch {
    return {}
  }
}

function write(edits: Record<string, Edit>) {
  try {
    localStorage.setItem(CACHE, JSON.stringify(edits))
  } catch {
    /* private window: the edits still go to Supabase */
  }
}
