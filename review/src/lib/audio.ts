import type { AudioClipInfo, AudioDoc, AudioFileInfo, AudioLayer, ClipStatus, Comment, Release, StateInfo } from './types'

/** The audio tracks, top to bottom, as an edit timeline numbers them. */
export const LANES: { layer: AudioLayer; name: string; track: string }[] = [
  { layer: 'voice', name: 'Voice', track: 'A1' },
  { layer: 'design', name: 'Sound design', track: 'A2' },
  { layer: 'music', name: 'Music', track: 'A3' },
]

export const STATUSES: { key: ClipStatus; label: string }[] = [
  { key: 'undone', label: 'Undone' },
  { key: 'needs-work', label: 'Needs work' },
  { key: 'done', label: 'Done' },
]

export const statusLabel = (s: ClipStatus) => STATUSES.find((x) => x.key === s)?.label ?? s

/** The release's audio.json, or null for a release built before the timeline existed. */
export async function loadAudio(release: Release): Promise<AudioDoc | null> {
  try {
    const r = await fetch(`${release.thumbBase}audio.json`, { cache: 'no-cache' })
    if (!r.ok) return null
    const doc = (await r.json()) as AudioDoc
    return Array.isArray(doc.clips) ? doc : null
  } catch {
    return null
  }
}

export const fileUrl = (release: Release, f: AudioFileInfo) =>
  f.audio ? (/^(https?:|blob:|data:)/.test(f.audio) ? f.audio : `${release.thumbBase}${f.audio}`) : null

const decoded = new Map<string, Uint8Array>()
export function peaksOf(f: AudioFileInfo): Uint8Array | null {
  if (!f.peaks) return null
  let p = decoded.get(f.peaks)
  if (!p) {
    const bin = atob(f.peaks)
    p = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) p[i] = bin.charCodeAt(i)
    decoded.set(f.peaks, p)
  }
  return p
}

/** A clip as the timeline draws it: the play, its file, and the sub-row of its lane it sits on. */
export interface Placed extends AudioClipInfo {
  id: string
  info: AudioFileInfo
  row: number
}

/**
 * What a status and a thread belong to. A state's cue and a state's line are slots whether or not
 * a file fills them — an empty one is a gap in the sheet, drawn across its state — and a bed is a
 * slot of its own however many times it is played.
 */
export interface Slot {
  key: string
  layer: AudioLayer
  state: StateInfo
  clips: Placed[]
  /** The gap's place on the timeline, and its sub-row, when nothing fills the slot. */
  missing: null | { start: number; end: number; row: number }
  start: number
  end: number
}

export interface Lane {
  layer: AudioLayer
  name: string
  track: string
  rows: number
  slots: Slot[]
}

/** The slot a state's cue or line stands in: the same rule tools/release_audio.py writes. */
export const slotFor = (layer: AudioLayer, state: string) => `${layer}:${state}`

/**
 * Every lane with its slots in time order, and every clip on the first sub-row it does not
 * overlap: a one-shot that rings on into the next state's cue drops a row, as a clip on an edit
 * timeline would.
 */
export function buildLanes(doc: AudioDoc, states: StateInfo[]): Lane[] {
  const byKey = new Map(states.map((s) => [s.key, s]))
  return LANES.map((lane) => {
    const slots = new Map<string, Slot>()
    doc.clips.forEach((c, i) => {
      if (c.layer !== lane.layer) return
      const state = byKey.get(c.state)
      if (!state) return
      const info = doc.files[c.file] ?? { name: c.file.split('/').pop() ?? c.file, dir: '' }
      let slot = slots.get(c.slot)
      if (!slot) {
        slot = { key: c.slot, layer: lane.layer, state, clips: [], missing: null, start: c.start, end: c.end }
        slots.set(c.slot, slot)
      }
      slot.clips.push({ ...c, id: `${i}`, info, row: 0 })
      slot.start = Math.min(slot.start, c.start)
      slot.end = Math.max(slot.end, c.end)
    })

    // The gaps. Every state is owed a cue. A line is owed where the state has text on the track the
    // film was voiced from — and only if the film was voiced at all, or every state would be a gap.
    if (lane.layer === 'design' || lane.layer === 'voice') {
      const voiced = doc.clips.filter((c) => c.layer === 'voice')
      const extended = voiced.some((c) => /\/extended\//.test(c.file))
      for (const s of states) {
        const key = slotFor(lane.layer, s.key)
        if (slots.has(key)) continue
        const owed = lane.layer === 'design' || (voiced.length > 0 && !!(extended ? s.voiceoverExtended : s.voiceover)?.trim())
        if (owed) slots.set(key, { key, layer: lane.layer, state: s, clips: [], missing: { start: s.start, end: s.end, row: 0 }, start: s.start, end: s.end })
      }
    }

    const ordered = [...slots.values()].sort((a, b) => a.start - b.start)
    const items: { start: number; end: number; set(row: number): void }[] = []
    for (const s of ordered) {
      if (s.missing) items.push({ start: s.missing.start, end: s.missing.end, set: (r) => { s.missing!.row = r } })
      for (const c of s.clips) items.push({ start: c.start, end: c.end, set: (r) => { c.row = r } })
    }
    items.sort((a, b) => a.start - b.start)
    const ends: number[] = []
    for (const it of items) {
      let r = ends.findIndex((e) => e <= it.start + 1e-6)
      if (r < 0) { r = ends.length; ends.push(0) }
      ends[r] = it.end
      it.set(r)
    }
    return { ...lane, rows: Math.max(1, ends.length), slots: ordered }
  })
}

export interface StatusEntry {
  status: ClipStatus
  row: Comment | null
}

/** Each slot's status: the latest status row written for it, in any release. */
export function statusesOf(comments: Comment[]): Map<string, StatusEntry> {
  const out = new Map<string, StatusEntry>()
  const rows = comments.filter((c) => c.kind === 'audio_status' && c.clip).sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  for (const r of rows) {
    const s = r.body as ClipStatus
    if (s === 'undone' || s === 'needs-work' || s === 'done') out.set(r.clip!, { status: s, row: r })
  }
  return out
}

/** The name a slot goes by: its file, or for a gap what the sheet would call it. */
export function slotName(slot: Slot): string {
  if (slot.clips.length) return slot.clips[0].info.name
  return slot.layer === 'voice' ? `${slot.state.key}.wav` : `…-${slot.state.key}.wav`
}

export const layerName = (l: AudioLayer) => LANES.find((x) => x.layer === l)?.name ?? l

/** m:ss.cc, for places in a film where a frame matters. */
export function clock(t: number): string {
  const sign = t < 0 ? '-' : ''
  t = Math.abs(t)
  const m = Math.floor(t / 60)
  const s = t - m * 60
  return `${sign}${m}:${s.toFixed(2).padStart(5, '0')}`
}

export const seconds = (t: number) => (t < 10 ? `${t.toFixed(2)} s` : t < 60 ? `${t.toFixed(1)} s` : clock(t))

// --- gain ------------------------------------------------------------------------------ //

/** What the timeline's peaks are drawn over, as tools/release_audio.py writes them. */
export const FLOOR_DB = 60
/**
 * How far a reviewed gain may go either way, in dB. The show plays a source up to +40 dB
 * (Speakers.MAX_MIX), so +30 on a cue is heard as set while the layer's fader stays under +10.
 */
export const GAIN_MIN = -30
export const GAIN_MAX = 30

export const dbToGain = (db: number) => Math.pow(10, db / 20)
export const gainToDb = (g: number) => (g > 0 ? 20 * Math.log10(g) : -Infinity)

/** "+3.0 dB", "−4.5 dB", "0 dB". */
export function fmtDb(db: number): string {
  if (Math.abs(db) < 0.05) return '0 dB'
  return `${db > 0 ? '+' : '−'}${Math.abs(db).toFixed(1)} dB`
}

export interface GainEntry {
  db: number
  row: Comment
}

/** Each slot's reviewed gain: the latest gain row written for it, in any release. */
export function gainsOf(comments: Comment[]): Map<string, GainEntry> {
  const out = new Map<string, GainEntry>()
  const rows = comments.filter((c) => c.kind === 'audio_gain' && c.clip).sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  for (const r of rows) {
    const db = Number(r.body)
    if (Number.isFinite(db)) out.set(r.clip!, { db, row: r })
  }
  return out
}

/**
 * The gain a clip was filmed at, in dB against its file: the cue log's gain with the layer's mix taken
 * out. A film made after a gain was reviewed carries that gain here, so the review can tell a gain
 * that is in the film from one still waiting for the next release.
 */
export function filmDb(doc: AudioDoc, c: AudioClipInfo): number {
  const mix = doc.mix?.[c.layer] ?? 1
  const db = gainToDb(c.gain / (mix || 1))
  return Number.isFinite(db) ? Math.round(db * 10) / 10 : 0
}
