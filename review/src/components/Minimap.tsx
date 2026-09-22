import { useEffect, useRef } from 'react'
import type { StateInfo } from '../lib/types'

export interface Counts {
  total: number
  open: number
  unread: number
}

interface Props {
  states: StateInfo[]
  current: number
  time: number
  counts: Map<string, Counts>
  thumbUrl(s: StateInfo): string
  onSelect(index: number): void
}

/** Every state of the film as a strip along the foot of the page, grouped under its chapter. */
export default function Minimap({ states, current, time, counts, thumbUrl, onSelect }: Props) {
  const strip = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = strip.current?.querySelector<HTMLElement>(`[data-index="${current}"]`)
    el?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  }, [current])

  // group consecutive states by chapter/moment
  const groups: { label: string; kind: string; states: StateInfo[] }[] = []
  for (const s of states) {
    const last = groups[groups.length - 1]
    const label = s.chapter || (s.slideKind === 'backdrop' ? 'Wall' : '')
    if (last && last.label === label) last.states.push(s)
    else groups.push({ label, kind: s.chapterKind, states: [s] })
  }

  return (
    <div className="minimap" ref={strip}>
      {groups.map((g, gi) => (
        <div className={`chapter-group kind-${g.kind || 'none'}`} key={gi}>
          <div className="chapter-label" title={g.label}>{g.label || ' '}</div>
          <div className="chapter-states">
            {g.states.map((s) => {
              const c = counts.get(s.key)
              const isCurrent = s.index === current
              const progress = isCurrent ? Math.min(1, Math.max(0, (time - s.start) / Math.max(0.001, s.end - s.start))) : 0
              return (
                <button
                  key={s.key}
                  data-index={s.index}
                  className={`thumb${isCurrent ? ' current' : ''}${c?.unread ? ' unread' : ''}`}
                  onClick={() => onSelect(s.index)}
                  title={`${s.key} · ${s.title}`}
                >
                  <img src={thumbUrl(s)} alt="" loading="lazy" draggable={false} />
                  {c && c.total > 0 && (
                    <span className={`badge${c.open ? '' : ' all-done'}`}>{c.open || c.total}</span>
                  )}
                  {c?.unread ? <span className="dot" /> : null}
                  <span className="thumb-label">{s.letter === 'A' ? s.slide : s.letter}</span>
                  <span className="progress" style={{ width: `${progress * 100}%` }} />
                </button>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}
