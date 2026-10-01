import type { Manifest, StateInfo } from '../lib/types'

/**
 * The film the client practises on, read off `public/releases/practice.json`:
 *
 *     { "cuts": [ { "slug": "2026-09-28", "created": "2026-09-28", "folder": "2026-09-28", "audio": "novoice" } ] }
 *
 * **Only the newest cut is ever shown**: the latest `created`, and of two on one day the one listed
 * last. There is no choosing between versions on the page, and its head says which one it is.
 *
 * `folder` is a release folder under `releases/` — a review release, or a practice cut filmed without
 * subtitles (`releases/practice/<slug>`). **`audio` says which sound may be played, and nothing plays
 * unless it says so**: the key of one of the manifest's extra soundtracks (`novoice`, the mix without
 * the voice-over), played with the film muted; or `film`, for a cut whose own soundtrack has no voice
 * in it. Left out, or naming a track the manifest does not have, the page is silent — the voice-over
 * must never reach this page.
 */
export interface Cut {
  slug: string
  name: string
  created: string
  videoUrl: string
  /** A soundtrack to play over the muted film; null plays the film's own sound, or none. */
  audioUrl: string | null
  /** Whether the film's own sound may be heard: only on a cut that says its sound is voice-free. */
  filmSound: boolean
  thumbBase: string
  fps: number
  duration: number
  states: StateInfo[]
}

interface CutEntry {
  slug: string
  name?: string
  created?: string
  folder: string
  audio?: string
}

const base = import.meta.env.BASE_URL.replace(/\/$/, '')
const absolute = (s: string) => /^(https?:|blob:|data:|\/)/.test(s)

export async function loadCut(): Promise<Cut | null> {
  const index = await fetch(`${base}/releases/practice.json`, { cache: 'no-cache' }).then((r) => (r.ok ? r.json() : null))
  const cuts: CutEntry[] = index?.cuts ?? []
  const entry = cuts.reduce<CutEntry | undefined>((newest, c) => (!newest || (c.created ?? '') >= (newest.created ?? '') ? c : newest), undefined)
  if (!entry) return null
  const folder = `${base}/releases/${entry.folder}`
  const m = (await fetch(`${folder}/manifest.json`, { cache: 'no-cache' }).then((r) => r.json())) as Manifest
  const track = entry.audio && entry.audio !== 'film' ? m.audio?.find((a) => a.key === entry.audio) : undefined
  return {
    slug: entry.slug,
    name: entry.name ?? m.name,
    created: entry.created ?? m.created,
    videoUrl: absolute(m.video) ? m.video : `${folder}/${m.video}`,
    audioUrl: track ? (absolute(track.file) ? track.file : `${folder}/${track.file}`) : null,
    filmSound: entry.audio === 'film',
    thumbBase: `${folder}/`,
    fps: m.fps,
    duration: m.duration,
    // what the film says is not this page's to show: the lines are dropped as the cut is read
    states: m.states.map(({ voiceover: _v, voiceoverExtended: _x, ...s }) => s),
  }
}

/** A run of the running order under one heading: a chapter of the talk, or a moment around it. */
export interface Section {
  index: number
  name: string
  kind: 'chapter' | 'moment'
  /** 1, 2, 3 … for the chapters; 0 for a moment. */
  number: number
  first: number
  last: number
  slides: Slide[]
}

/** One slide as the client knows it: a picture, clicked through its states. */
export interface Slide {
  /** 1-based, in the running order. */
  number: number
  id: string
  title: string
  section: Section
  /** Indices into the cut's states, one per click. */
  states: number[]
}

export interface Deck {
  sections: Section[]
  slides: Slide[]
  /** For each state, its slide. */
  slideOf: Slide[]
}

/** The running order folded into sections and slides, as consecutive runs of the manifest's states. */
export function deckOf(states: StateInfo[]): Deck {
  const sections: Section[] = []
  const slides: Slide[] = []
  const slideOf: Slide[] = []
  let chapters = 0
  states.forEach((s, i) => {
    let section = sections[sections.length - 1]
    if (!section || section.name !== s.chapter) {
      const kind = s.chapterKind === 'chapter' ? 'chapter' : 'moment'
      section = { index: sections.length, name: s.chapter, kind, number: kind === 'chapter' ? ++chapters : 0, first: i, last: i, slides: [] }
      sections.push(section)
    }
    section.last = i
    let slide = slides[slides.length - 1]
    if (!slide || slide.id !== s.slide || slide.section !== section) {
      slide = { number: slides.length + 1, id: s.slide, title: s.title, section, states: [] }
      slides.push(slide)
      section.slides.push(slide)
    }
    slide.states.push(i)
    slideOf.push(slide)
  })
  return { sections, slides, slideOf }
}

export const thumbUrl = (cut: Cut, s: StateInfo) => (absolute(s.thumb) ? s.thumb : `${cut.thumbBase}${s.thumb}`)

/**
 * How a state's thumbnail is framed: a slide is the right-hand pane beside its chapter card, so only
 * that half says which slide it is; a backdrop or a scene takes the whole wall and is shown whole.
 */
export const thumbFrame = (s: StateInfo) => (s.slideKind === 'slide' ? 'pane' : 'wall')
