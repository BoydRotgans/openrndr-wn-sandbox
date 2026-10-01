import { useEffect, useRef } from 'react'
import { sectionName, slideTitle, type copy } from './copy'
import { thumbFrame, thumbUrl, type Cut, type Deck, type Section } from './deck'

interface Props {
  t: (typeof copy)['nl']
  cut: Cut
  deck: Deck
  current: number
  hasNote(stateKey: string): boolean
  onPick(stateIndex: number): void
  onClose(): void
}

/**
 * Every slide of the evening, grouped under its chapter or the moment it stands in, to jump to. A card
 * goes to the slide's first click, and the numbered chips under it to any one click.
 */
export default function Overview({ t, cut, deck, current, hasNote, onPick, onClose }: Props) {
  const here = useRef<HTMLDivElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const slide = deck.slideOf[current]

  useEffect(() => {
    here.current?.scrollIntoView({ block: 'center' })
  }, [])

  const jumpTo = (s: Section) => {
    list.current?.querySelector(`[data-section="${s.index}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <div className="overview" role="dialog" aria-modal="true" aria-label={t.overview}>
      <div className="overview-head">
        <div>
          <h2>{t.overview}</h2>
          <p>{t.pickHint}</p>
        </div>
        <button className="ghost big" onClick={onClose}>{t.close} <kbd>Esc</kbd></button>
      </div>
      <nav className="overview-index">
        {deck.sections.map((s) => (
          <button key={s.index} className={`index-chip ${s.kind}${s === slide?.section ? ' on' : ''}`} onClick={() => jumpTo(s)}>
            {s.kind === 'chapter' ? <b>{s.number}</b> : null}
            {s.kind === 'chapter' ? sectionName(s.name) : t.moments[s.name] ?? s.name}
          </button>
        ))}
      </nav>
      <div className="overview-list" ref={list}>
        {deck.sections.map((s) => (
          <section key={s.index} data-section={s.index} className={`ov-section ${s.kind}`}>
            <h3>
              {s.kind === 'chapter' ? <span className="ov-label">{t.chapter(s.number)}</span> : <span className="ov-label">{t.moment}</span>}
              {s.kind === 'chapter' ? sectionName(s.name) : t.moments[s.name] ?? s.name}
            </h3>
            <div className="ov-grid">
              {s.slides.map((sl) => {
                const on = sl === slide
                const last = cut.states[sl.states[sl.states.length - 1]]
                const title = slideTitle(t, sl.title, s.kind === 'moment')
                return (
                  <div key={sl.number} ref={on ? here : undefined} className={`ov-card${on ? ' on' : ''}`}>
                    <button className={`ov-thumb ${thumbFrame(last)}`} onClick={() => onPick(sl.states[0])} title={title}>
                      <img src={thumbUrl(cut, last)} alt="" loading="lazy" />
                      <span className="ov-number">{sl.number}</span>
                    </button>
                    <div className="ov-meta">
                      <button className="ov-title" onClick={() => onPick(sl.states[0])}>{title}</button>
                      <div className="ov-clicks">
                        {sl.states.map((i, n) => {
                          const key = cut.states[i].key
                          const noted = hasNote(key)
                          return (
                            <button
                              key={i}
                              className={`ov-click${i === current ? ' on' : ''}${noted ? ' noted' : ''}`}
                              onClick={() => onPick(i)}
                              title={`${t.clickOf(n + 1, sl.states.length)}${noted ? ` · ${t.hasNote}` : ''}`}
                            >
                              {n + 1}
                            </button>
                          )
                        })}
                        <span className="ov-count">{t.clicks(sl.states.length)}</span>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </section>
        ))}
      </div>
    </div>
  )
}
