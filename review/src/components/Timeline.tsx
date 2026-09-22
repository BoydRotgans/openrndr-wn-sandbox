import { useLayoutEffect, useRef, useState } from 'react'
import { formatTime } from '../lib/csv'
import type { StateInfo } from '../lib/types'
import { TEAM, colourOf } from '../lib/team'

export interface NoteMark {
  id: string
  key: string
  at: number
  assignees: string[]
  author: string
  body: string
  done: boolean
  replies: number
  topic: string
}

interface Props {
  notes: NoteMark[]
  states: StateInfo[]
  duration: number
  time: number
  current: number
  thumbUrl(s: StateInfo): string
  /** Scrubbing the bar: moves the playhead and keeps playing or paused as it was. */
  onSeek(t: number): void
  /** A section: goes there and plays. */
  onJump(t: number): void
  /** A note's mark: goes to its frame with the note open. */
  onJumpNote(id: string, t: number): void
}

/** The whole film as one bar: chapters along the top, a segment per state, the playhead over it. */
export default function Timeline({ notes, states, duration, time, current, thumbUrl, onSeek, onJump, onJumpNote }: Props) {
  const bar = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState<number | null>(null)
  const [hoverNote, setHoverNote] = useState<string | null>(null)
  const [hoverSection, setHoverSection] = useState<string | null>(null)
  const dragging = useRef(false)

  const timeAt = (clientX: number) => {
    const r = bar.current!.getBoundingClientRect()
    const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width))
    return f * duration
  }

  const groups: { label: string; kind: string; start: number; end: number; number: number }[] = []
  let chapters = 0
  for (const s of states) {
    const last = groups[groups.length - 1]
    const label = s.chapter
    if (last && last.label === label && last.kind === s.chapterKind) last.end = s.end
    else groups.push({ label, kind: s.chapterKind, start: s.start, end: s.end, number: s.chapterKind === 'chapter' ? ++chapters : 0 })
  }

  const pct = (t: number) => `${(t / Math.max(duration, 0.001)) * 100}%`
  const hoverState = hover != null ? states.find((s) => hover >= s.start && hover < s.end) : null

  // The rows share the bar's axis: a button starts where its section starts in the film.
  // Chapters are long enough to span their length; a wall is seconds in a twenty-minute
  // film, so it keeps the width its name needs and drops to a lower lane where it would
  // overlap the one before.
  const track = useRef<HTMLDivElement>(null)
  const [trackWidth, setTrackWidth] = useState(1000)
  useLayoutEffect(() => {
    const el = track.current
    if (!el) return
    const measure = () => setTrackWidth(el.getBoundingClientRect().width)
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const px = (t: number) => (t / Math.max(duration, 0.001)) * trackWidth
  // One row, and every box is exactly its span on the axis — a clip on an edit timeline. A
  // name that does not fit is clipped and read off the hover card instead.
  const lay = (items: typeof groups) =>
    items.map((g) => ({ g, left: px(g.start), width: Math.max(2, px(g.end - g.start) - 1), lane: 0 }))
  const rows = [
    { name: 'Walls', kind: 'moment', items: lay(groups.filter((g) => g.kind !== 'chapter')) },
    { name: 'Chapters', kind: 'chapter', items: lay(groups.filter((g) => g.kind === 'chapter')) },
  ]
  const now = states[current]

  return (
    <div className="timeline">
      {rows.map((row) => {
        const lanes = row.items.reduce((m, it) => Math.max(m, it.lane + 1), 1)
        return (
          <div key={row.name} className={`timeline-row row-${row.kind}`}>
            <div className="tl-row-name">{row.name}</div>
            <div className="tl-row-track" ref={row.kind === 'moment' ? track : undefined} style={{ height: lanes * 28 - 2 }}>
              {row.items.map(({ g, left, width, lane }, i) => {
                const active = now != null && now.start >= g.start && now.start < g.end
                const key = `${row.kind}-${i}`
                return (
                  <div key={key} className="tl-section-wrap" style={{ left, width, top: lane * 28 }} onMouseEnter={() => setHoverSection(key)} onMouseLeave={() => setHoverSection(null)}>
                    <button className={`tl-section${active ? ' active' : ''}`} onClick={() => onJump(g.start)}>
                      {g.number > 0 && <b>{g.number}</b>}
                      <span>{g.label || 'wall'}</span>
                      <em>{formatTime(g.start)}</em>
                    </button>
                    {hoverSection === key && (
                      <div className={`tl-section-card${left / trackWidth > 0.8 ? ' at-right' : ''}`}>
                        {g.number > 0 && <b>{g.number} · </b>}{g.label || 'Wall'}
                        <em>{formatTime(g.start)} – {formatTime(g.end)}</em>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        )
      })}
      <div className="timeline-row row-bar">
      <div className="tl-row-name">Timeline</div>
      <div
        className="timeline-bar"
        ref={bar}
        onPointerDown={(e) => {
          dragging.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          onSeek(timeAt(e.clientX))
        }}
        onPointerMove={(e) => {
          setHover(timeAt(e.clientX))
          if (dragging.current) onSeek(timeAt(e.clientX))
        }}
        onPointerUp={() => { dragging.current = false }}
        onPointerLeave={() => { setHover(null); dragging.current = false }}
      >
        {states.map((s) => (
          <div
            key={s.key}
            className={`tl-state${s.index === current ? ' current' : ''}${s.step === 0 ? ' first' : ''}`}
            style={{ left: pct(s.start), width: pct(s.end - s.start) }}
          />
        ))}
        {groups.map((g, i) => (
          <div key={`g${i}`} className={`tl-group kind-${g.kind || 'none'}`} style={{ left: pct(g.start), width: pct(g.end - g.start) }} />
        ))}
        <div className="tl-played" style={{ width: pct(time) }} />
        <div className="tl-head" style={{ left: pct(time) }} />
        {hover != null && (
          <div className="tl-hover" style={{ left: `clamp(100px, ${pct(hover)}, calc(100% - 100px))` }}>
            {hoverState && <img src={thumbUrl(hoverState)} alt="" />}
            <span>{formatTime(hover)}{hoverState ? ` · ${hoverState.key}` : ''}</span>
          </div>
        )}
      </div>
      </div>
      <div className="timeline-row row-notes">
        <div className="tl-row-name">Notes</div>
        <div className="tl-row-track tl-notes">
          {notes.map((n) => {
            const colours = n.assignees.map(colourOf)
            const background = colours.length > 1
              ? `linear-gradient(90deg, ${colours.map((c, i) => `${c} ${(i / colours.length) * 100}% ${((i + 1) / colours.length) * 100}%`).join(', ')})`
              : colours[0] ?? 'var(--warn)'
            return (
              <div key={n.id} className="tl-mark-wrap" style={{ left: pct(n.at) }} onMouseEnter={() => setHoverNote(n.id)} onMouseLeave={() => setHoverNote(null)}>
                <button className={`tl-note${n.done ? ' done' : ''}${n.id === hoverNote ? ' hover' : ''}`} style={{ background }} onClick={() => onJumpNote(n.id, n.at)} />
                {hoverNote === n.id && (
                  <div className={`tl-mark-card${n.at / duration < 0.12 ? ' at-left' : n.at / duration > 0.88 ? ' at-right' : ''}`}>
                    <div className="tl-mark-title">{n.key} <em>{n.topic}</em></div>
                    <div className={`tl-mark-item${n.done ? ' done' : ''}`}>
                      <b>{n.author}</b>
                      {n.assignees.map((a) => <span key={a} className="assignee" style={{ color: colourOf(a) }}> → {a}</span>)}
                      <div>{n.body}</div>
                      {n.replies > 0 && <div className="tl-mark-replies">{n.replies} {n.replies === 1 ? 'reply' : 'replies'}</div>}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
      <div className="timeline-row row-legend">
        <div className="tl-row-name" />
        <div className="tl-legend">
          <span><i style={{ background: 'var(--warn)' }} />unassigned</span>
          {TEAM.map((p) => (
            <span key={p}><i style={{ background: colourOf(p) }} />{p}</span>
          ))}
          <span><i className="faded" />done</span>
        </div>
      </div>
    </div>
  )
}
