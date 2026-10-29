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
 *
 * **The subtitles come as text, beside the film rather than in it** (asked 2 October): [Cut.lines] is
 * what is said over each click — on the track the cut's voice was rendered from where its manifest says
 * (`track`, the aligned one since 15 October), else the extended track where a click has a line there and
 * the standard one otherwise. The film stays a clean cut; nothing is heard.
 *
 * **The autocue comes as a film of its own** (15 October): `autocue` in the manifest is the presenter's
 * autocue filmed on the same run, frame for frame with the film and silent, which the page plays under it.
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
  /** The film's width over its height: 32:9 on the 1080 wall, 3840:730 since 27 October. */
  ratio: number
  states: StateInfo[]
  /** What is said over each click, by its key: the extended line, else the standard one; none where silent. */
  lines: Record<string, string>
  /** The speaker's sheets made off this cut, as PDFs beside its film: `uitgebreid` and `standaard`. */
  sheets: { key: string; url: string }[]
  /** The autocue filmed on the same run, to play under the film in step with it; null where the cut has none. */
  autocueUrl: string | null
}

interface CutEntry {
  slug: string
  name?: string
  created?: string
  folder: string
  audio?: string
  /** The speaker's sheets in the cut's folder, listed so the page offers them. */
  sheets?: { key: string; file: string }[]
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
    ratio: m.width && m.height ? m.width / m.height : 32 / 9,
    // The lines are kept apart from the states, for the subtitle over the film; nothing is played.
    states: m.states.map(({ voiceover: _v, voiceoverExtended: _x, voiceoverAligned: _a, voiceoverErik: _e, ...s }) => s),
    lines: Object.fromEntries(m.states.flatMap((s) => {
      // the track the voice was rendered from, where the cut names it: a click silent there stays silent
      const line = (m.track === 'erik' ? s.voiceoverErik
        : m.track === 'aligned' ? s.voiceoverAligned
        : m.track === 'extended' ? s.voiceoverExtended
        : m.track === 'default' ? s.voiceover
        : s.voiceoverExtended || s.voiceover) ?? ''
      return line.trim() ? [[s.key, line.trim()]] : []
    })),
    sheets: (entry.sheets ?? []).map((x) => ({ key: x.key, url: absolute(x.file) ? x.file : `${folder}/${x.file}` })),
    autocueUrl: m.autocue ? (absolute(m.autocue) ? m.autocue : `${folder}/${m.autocue}`) : null,
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
