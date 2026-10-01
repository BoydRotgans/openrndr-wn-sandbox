import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { colourOf } from '../lib/team'
import { FLOOR_DB, clock, fmtDb, peaksOf, seconds, statusLabel, type Lane, type Placed, type Slot } from '../lib/audio'
import type { ClipStatus, StateInfo } from '../lib/types'

const RULER_H = 26
const CHAPTER_H = 22
/** A track's sub-row is as tall as the page leaves room for, within these. */
const ROW_MIN = 34
const ROW_MAX = 88
/** Ruler steps, in seconds; the finest whose labels stand this far apart is used. */
const STEPS = [0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600]
const LABEL_GAP = 84

export interface SlotNotes {
  open: number
  total: number
  /** Who the open notes are for. */
  people: string[]
}

interface Props {
  duration: number
  states: StateInfo[]
  lanes: Lane[]
  time: number
  playing: boolean
  /** Pixels a second: the zoom. */
  pps: number
  minPps: number
  maxPps: number
  onZoom(pps: number): void
  /** The width the tracks have to show the film in, for "fit". */
  onViewport(width: number): void
  selected: string | null
  onSelect(slot: Slot, clip: Placed | null): void
  onSeek(t: number): void
  statusOf(key: string): ClipStatus
  notesOf(key: string): SlotNotes | undefined
  /** A sound-design slot's reviewed gain, and the gain the film was made at, in dB against its file. */
  gainOf(key: string): { db: number; film: number }
  matches(slot: Slot): boolean
  thumbUrl(s: StateInfo): string
  follow: boolean
  /** Brings a time into view when `n` changes: a selection made off the keyboard or a link. */
  reveal: { t: number; end: number; n: number } | null
}

/**
 * The film on its side, as an edit timeline: a ruler, the chapters, the film's states as its video
 * track, and one track per layer of the mix with every sound the film played laid on it where it
 * played — a missing cue drawn as a gap across its state. The wheel scrolls it sideways, ⌘/ctrl
 * and the wheel (or a pinch) zoom about the pointer.
 */
export default function AudioTimeline(p: Props) {
  const root = useRef<HTMLDivElement>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const [view, setView] = useState({ left: 0, width: 1000 })
  const width = Math.max(view.width, p.duration * p.pps)

  // The tracks take the height there is: every sub-row and the film share it, so a tall window
  // draws taller waveforms rather than empty page under the timeline.
  const [height, setHeight] = useState(0)
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    const measure = () => setHeight(el.clientHeight)
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  const rows = p.lanes.reduce((n, l) => n + l.rows, 0)
  const rowH = Math.round(Math.min(ROW_MAX, Math.max(ROW_MIN, (height - RULER_H - CHAPTER_H - 4 - p.lanes.length - 3) / (rows + 1))))
  const filmH = rowH + 4

  // --- zoom, keeping a time where it was on screen -------------------------------- //

  const anchor = useRef<{ t: number; x: number } | null>(null)
  const lastPps = useRef(p.pps)
  const zoomTo = (next: number, at?: { t: number; x: number }) => {
    const z = Math.min(p.maxPps, Math.max(p.minPps, next))
    if (z === p.pps) return
    anchor.current = at ?? null
    p.onZoom(z)
  }
  useLayoutEffect(() => {
    const el = scroller.current
    if (!el || lastPps.current === p.pps) return
    const old = lastPps.current
    lastPps.current = p.pps
    // about the pointer; else about the playhead if it is on screen; else about the middle
    let a = anchor.current
    anchor.current = null
    if (!a) {
      const head = p.time * old - el.scrollLeft
      a = head >= 0 && head <= el.clientWidth
        ? { t: p.time, x: head }
        : { t: (el.scrollLeft + el.clientWidth / 2) / old, x: el.clientWidth / 2 }
    }
    el.scrollLeft = a.t * p.pps - a.x
    setView({ left: el.scrollLeft, width: el.clientWidth })
  }, [p.pps])

  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const measure = () => {
      setView({ left: el.scrollLeft, width: el.clientWidth })
      p.onViewport(el.clientWidth)
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    let raf = 0
    const onScroll = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => setView({ left: el.scrollLeft, width: el.clientWidth }))
    }
    el.addEventListener('scroll', onScroll)
    return () => { ro.disconnect(); el.removeEventListener('scroll', onScroll); cancelAnimationFrame(raf) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // The wheel is the timeline's: sideways as it is, and zoom with ⌘/ctrl (which is also what a
  // trackpad pinch arrives as). A listener of its own, because React's is passive and cannot
  // keep the page from scrolling instead.
  const zoomRef = useRef(zoomTo)
  zoomRef.current = zoomTo
  const ppsRef = useRef(p.pps)
  ppsRef.current = p.pps
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault()
        const x = e.clientX - el.getBoundingClientRect().left
        const t = (el.scrollLeft + x) / ppsRef.current
        zoomRef.current(ppsRef.current * Math.exp(-e.deltaY * 0.0025), { t, x })
      } else if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
        e.preventDefault()
        el.scrollLeft += e.deltaY
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  // --- keeping the playhead and the selection in view --------------------------- //

  useEffect(() => {
    const el = scroller.current
    if (!el || !p.follow || !p.playing) return
    const x = p.time * p.pps - el.scrollLeft
    if (x < el.clientWidth * 0.04 || x > el.clientWidth * 0.9) el.scrollLeft = p.time * p.pps - el.clientWidth * 0.2
  }, [p.time, p.follow, p.playing, p.pps])

  useEffect(() => {
    const el = scroller.current
    if (!el || !p.reveal) return
    const a = p.reveal.t * p.pps - el.scrollLeft
    const b = p.reveal.end * p.pps - el.scrollLeft
    if (a < 0 || a > el.clientWidth - 40 || (b > el.clientWidth && b - a < el.clientWidth)) {
      el.scrollTo({ left: Math.max(0, p.reveal.t * p.pps - el.clientWidth * 0.25), behavior: 'smooth' })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p.reveal?.n])

  // --- the page ----------------------------------------------------------------- //

  const selectedSpan = useMemo(() => {
    for (const lane of p.lanes) for (const s of lane.slots) if (s.key === p.selected) return { start: s.start, end: s.end }
    return null
  }, [p.lanes, p.selected])

  const laneStats = (lane: Lane) => {
    let clips = 0, missing = 0
    for (const s of lane.slots) { clips += s.clips.length; if (s.missing) missing++ }
    return { clips, missing }
  }

  return (
    <div className="atl" ref={root}>
      <div className="atl-heads">
        <div className="atl-head ruler" style={{ height: RULER_H }}>
          <span className="atl-tc">{clock(p.time)}</span>
        </div>
        <div className="atl-head small" style={{ height: CHAPTER_H }}>Chapters</div>
        <div className="atl-head" style={{ height: filmH }}>
          <b>V1</b><span>Film</span>
        </div>
        {p.lanes.map((lane) => {
          const st = laneStats(lane)
          return (
            <div key={lane.layer} className={`atl-head lane-${lane.layer}`} style={{ height: lane.rows * rowH }}>
              <b>{lane.track}</b>
              <span>{lane.name}<small>{st.clips} {st.clips === 1 ? 'clip' : 'clips'}{st.missing ? <em> · {st.missing} missing</em> : null}</small></span>
            </div>
          )
        })}
      </div>

      <div className="atl-scroll" ref={scroller}>
        <div className="atl-canvas" style={{ width }}>
          <Ruler duration={p.duration} pps={p.pps} left={view.left} width={view.width} onSeek={p.onSeek} />
          <Chapters states={p.states} pps={p.pps} />
          <Film states={p.states} pps={p.pps} height={filmH} thumbUrl={p.thumbUrl} onSeek={p.onSeek} selected={p.selected} />
          {selectedSpan && (
            <div className="atl-span" style={{ left: selectedSpan.start * p.pps, width: Math.max(2, (selectedSpan.end - selectedSpan.start) * p.pps), top: RULER_H }} />
          )}
          {p.lanes.map((lane) => (
            <LaneRow
              key={lane.layer}
              lane={lane}
              pps={p.pps}
              rowH={rowH}
              selected={p.selected}
              onSelect={p.onSelect}
              onSeek={p.onSeek}
              statusOf={p.statusOf}
              notesOf={p.notesOf}
              gainOf={p.gainOf}
              matches={p.matches}
            />
          ))}
          <div className="atl-playhead" style={{ left: p.time * p.pps }} />
        </div>
      </div>
    </div>
  )
}

/** Time along the top: ticks at a step that keeps the labels apart, drawn only where they can be seen. */
const Ruler = memo(function Ruler({ duration, pps, left, width, onSeek }: { duration: number; pps: number; left: number; width: number; onSeek(t: number): void }) {
  const step = STEPS.find((s) => s * pps >= LABEL_GAP) ?? STEPS[STEPS.length - 1]
  const minor = step / (step >= 60 ? 6 : step >= 10 ? 5 : step >= 1 ? 4 : 5)
  const from = Math.max(0, Math.floor((left - 100) / pps / step) * step)
  const to = Math.min(duration, (left + width + 100) / pps)
  const ticks: { t: number; major: boolean }[] = []
  for (let t = from; t <= to + 1e-9; t += minor) {
    const k = Math.round(t / minor)
    ticks.push({ t: k * minor, major: Math.abs(k * minor / step - Math.round(k * minor / step)) < 1e-6 })
  }
  const label = (t: number) => {
    const m = Math.floor(t / 60)
    const s = t - m * 60
    return step < 1 ? `${m}:${s.toFixed(1).padStart(4, '0')}` : `${m}:${Math.round(s).toString().padStart(2, '0')}`
  }
  const dragging = useRef(false)
  const timeAt = (e: React.PointerEvent) => {
    const r = e.currentTarget.getBoundingClientRect()
    return Math.min(duration, Math.max(0, (e.clientX - r.left) / pps))
  }
  return (
    <div
      className="atl-ruler"
      style={{ height: RULER_H }}
      onPointerDown={(e) => { dragging.current = true; e.currentTarget.setPointerCapture(e.pointerId); onSeek(timeAt(e)) }}
      onPointerMove={(e) => { if (dragging.current) onSeek(timeAt(e)) }}
      onPointerUp={() => { dragging.current = false }}
      title="Click or drag to move the playhead"
    >
      {ticks.map(({ t, major }) => (
        <div key={t.toFixed(3)} className={`atl-tick${major ? ' major' : ''}`} style={{ left: t * pps }}>
          {major && <span>{label(t)}</span>}
        </div>
      ))}
    </div>
  )
})

/** Chapters and moments as bands, their names held at the left edge while they scroll past. */
const Chapters = memo(function Chapters({ states, pps }: { states: StateInfo[]; pps: number }) {
  const groups: { label: string; kind: string; start: number; end: number; n: number }[] = []
  let n = 0
  for (const s of states) {
    const last = groups[groups.length - 1]
    if (last && last.label === s.chapter && last.kind === s.chapterKind) last.end = s.end
    else groups.push({ label: s.chapter, kind: s.chapterKind, start: s.start, end: s.end, n: s.chapterKind === 'chapter' ? ++n : 0 })
  }
  return (
    <div className="atl-chapters" style={{ height: CHAPTER_H }}>
      {groups.map((g, i) => (
        <div key={i} className={`atl-chapter kind-${g.kind || 'none'}`} style={{ left: g.start * pps, width: Math.max(1, (g.end - g.start) * pps - 1) }} title={`${g.n ? `${g.n} · ` : ''}${g.label || 'wall'} · ${clock(g.start)} – ${clock(g.end)}`}>
          <span>{g.n > 0 && <b>{g.n}</b>}{g.label || 'wall'}</span>
        </div>
      ))}
    </div>
  )
})

/** The film's states as the video track: a frame of each where there is room for one. */
const Film = memo(function Film({ states, pps, height, thumbUrl, onSeek, selected }: { states: StateInfo[]; pps: number; height: number; thumbUrl(s: StateInfo): string; onSeek(t: number): void; selected: string | null }) {
  const sel = selected ? selected.slice(selected.indexOf(':') + 1) : ''
  return (
    <div className="atl-film" style={{ height }}>
      {states.map((s) => {
        const w = (s.end - s.start) * pps
        return (
          <div
            key={s.key}
            className={`atl-state${s.step === 0 ? ' first' : ''}${s.key === sel ? ' sel' : ''}`}
            style={{ left: s.start * pps, width: Math.max(1, w - 1) }}
            onClick={() => onSeek(s.start)}
            title={`${s.key} · ${s.title} · ${clock(s.start)} – ${clock(s.end)}`}
          >
            {w > 34 && <img src={thumbUrl(s)} alt="" loading="lazy" draggable={false} />}
            {w > 70 && <span>{s.key}</span>}
          </div>
        )
      })}
    </div>
  )
})

interface LaneProps {
  lane: Lane
  pps: number
  rowH: number
  selected: string | null
  onSelect(slot: Slot, clip: Placed | null): void
  onSeek(t: number): void
  statusOf(key: string): ClipStatus
  notesOf(key: string): SlotNotes | undefined
  gainOf(key: string): { db: number; film: number }
  matches(slot: Slot): boolean
}

/** One audio track: every clip on its sub-row, and every gap. A click on bare track moves the playhead. */
const LaneRow = memo(function LaneRow({ lane, pps, rowH, selected, onSelect, onSeek, statusOf, notesOf, gainOf, matches }: LaneProps) {
  return (
    <div
      className={`atl-lane lane-${lane.layer}`}
      style={{ height: lane.rows * rowH }}
      onClick={(e) => {
        if (e.target !== e.currentTarget) return
        onSeek((e.clientX - e.currentTarget.getBoundingClientRect().left) / pps)
      }}
    >
      {Array.from({ length: lane.rows - 1 }, (_, i) => <div key={i} className="atl-subrow" style={{ top: (i + 1) * rowH }} />)}
      {lane.slots.map((slot) => {
        const status = statusOf(slot.key)
        const notes = notesOf(slot.key)
        const dim = !matches(slot)
        const sel = slot.key === selected
        // numbers rather than the object, so a slot whose gain did not move is not drawn again
        const g = lane.layer === 'design' && slot.clips.length ? gainOf(slot.key) : null
        return (
          <SlotView key={slot.key} slot={slot} pps={pps} rowH={rowH} status={status} notes={notes} dim={dim} sel={sel} onSelect={onSelect}
            gainDb={g?.db ?? null} filmDb={g?.film ?? 0} />
        )
      })}
    </div>
  )
})

function Badges({ notes }: { notes?: SlotNotes }) {
  if (!notes || !notes.total) return null
  return (
    <span className="aclip-badges">
      {notes.people.map((who) => (
        <i key={who} className="aclip-who" style={{ background: colourOf(who) }} title={`Open for ${who}`}>{who.charAt(0)}</i>
      ))}
      <em className={notes.open ? 'open' : ''} title={`${notes.open} open of ${notes.total} notes`}>{notes.open || notes.total}</em>
    </span>
  )
}

const SlotView = memo(function SlotView({ slot, pps, rowH, status, notes, dim, sel, onSelect, gainDb, filmDb }: {
  slot: Slot; pps: number; rowH: number; status: ClipStatus; notes?: SlotNotes; dim: boolean; sel: boolean; onSelect(slot: Slot, clip: Placed | null): void
  /** Sound design only: the reviewed gain, and what the film was made at. Null on the other tracks. */
  gainDb: number | null; filmDb: number
}) {
  const cls = `aclip s-${status}${sel ? ' sel' : ''}${dim ? ' dim' : ''}`
  if (slot.missing) {
    const m = slot.missing
    const w = Math.max(3, (m.end - m.start) * pps)
    return (
      <div
        className={`${cls} missing`}
        style={{ left: m.start * pps, width: w, top: m.row * rowH + 2, height: rowH - 4 }}
        onClick={(e) => { e.stopPropagation(); onSelect(slot, null) }}
        title={`Missing: no ${slot.layer === 'voice' ? 'voice line' : 'sound design'} on ${slot.state.key}\n${slot.state.title}\n${clock(m.start)} – ${clock(m.end)}\nStatus: ${status === 'done' ? 'silent on purpose' : statusLabel(status)}`}
      >
        <div className="aclip-label">
          <i className="aclip-dot" />
          <span className="aclip-name">{status === 'done' ? 'silent' : 'missing'} · {slot.state.key}</span>
          <Badges notes={notes} />
        </div>
      </div>
    )
  }
  return (
    <>
      {slot.clips.map((c) => {
        const w = Math.max(3, (c.end - c.start) * pps)
        const f = c.info
        const head = f.head ?? 0
        const db = gainDb ?? 0
        const pending = gainDb != null && Math.abs(db - filmDb) >= 0.05
        const clips = gainDb != null && !f.silent && (f.peakDb ?? -99) + db > 0
        return (
          <div
            key={c.id}
            className={`${cls}${f.silent ? ' silent' : ''}${f.absent ? ' absent' : ''}${clips ? ' clipping' : ''}`}
            style={{ left: c.start * pps, width: w, top: c.row * rowH + 2, height: rowH - 4 }}
            onClick={(e) => { e.stopPropagation(); onSelect(slot, c) }}
            title={[
              f.name, f.dir, `${slot.state.key} · ${slot.state.title}`,
              `${clock(c.start)} – ${clock(c.end)} (${seconds(c.end - c.start)})`,
              f.duration != null ? `file ${seconds(f.duration)}${f.peakDb != null ? ` · peak ${f.peakDb.toFixed(1)} dB` : ''}${f.lufs != null ? ` · ${f.lufs.toFixed(1)} LUFS` : ''}` : 'file not measured',
              gainDb != null ? `gain ${fmtDb(db)}${pending ? ` (in the film: ${fmtDb(filmDb)}; waits for the next release)` : ''}${clips ? ' — CLIPS at this gain' : ''}` : '',
              f.silent ? 'SILENT FILE' : head > 0.25 ? `${head.toFixed(2)} s of silence before it starts` : '',
              `Status: ${statusLabel(status)}`,
            ].filter(Boolean).join('\n')}
          >
            <Wave clip={c} shift={db} />
            {!f.silent && head > 0.25 && <div className="aclip-head" style={{ width: Math.min(w, head * pps) }} />}
            {c.released != null && c.end > c.released && (
              <div className="aclip-fade" style={{ left: (c.released - c.start) * pps, width: (c.end - c.released) * pps }} />
            )}
            <div className="aclip-label">
              <i className="aclip-dot" />
              <span className="aclip-name">{f.name}</span>
              {f.silent && <span className="aclip-flag">silent</span>}
              {gainDb != null && (Math.abs(db) >= 0.05 || pending) && (
                <span className={`aclip-gain${pending ? ' pending' : ''}`}>{fmtDb(db)}</span>
              )}
              {clips && <span className="aclip-flag">clips</span>}
              <Badges notes={notes} />
            </div>
          </div>
        )
      })}
    </>
  )
})

/**
 * The file's own peaks across the clip, in dB so a quiet cue still has a shape and a silent one is
 * flat. Drawn in peak units and stretched to the clip, so a zoom only rescales it.
 */
const Wave = memo(function Wave({ clip, shift = 0 }: { clip: Placed; shift?: number }) {
  const d = useMemo(() => {
    const peaks = peaksOf(clip.info)
    const rate = clip.info.peakRate ?? 40
    if (!peaks || !peaks.length) return null
    const n = Math.max(2, Math.ceil((clip.end - clip.start) * rate))
    // at the level it will play at: the reviewed gain moves every peak up or down the dB scale
    const lift = (shift * 255) / FLOOR_DB
    // a playlist that picked up where it stopped is drawn from there, not from the top of the file
    const from = Math.round((clip.offset ?? 0) * rate)
    const raw = (j: number) => { const i = j + from; return clip.loop ? peaks[i % peaks.length] : i < peaks.length ? peaks[i] : 0 }
    const at = (i: number) => { const v = raw(i); return v <= 0 ? 0 : Math.min(255, Math.max(0, v + lift)) }
    const top: string[] = ['M0 128']
    const bottom: string[] = []
    for (let i = 0; i < n; i++) {
      const h = (at(i) / 255) * 124
      top.push(`L${i} ${(128 - h).toFixed(1)}`)
      bottom.push(`L${i} ${(128 + h).toFixed(1)}`)
    }
    return { path: `${top.join('')}${bottom.reverse().join('')}Z`, n }
  }, [clip, shift])
  if (!d) return null
  return (
    <svg className="aclip-wave" viewBox={`0 0 ${d.n - 1} 256`} preserveAspectRatio="none" aria-hidden="true">
      <path d={d.path} />
    </svg>
  )
})

