import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import Film, { type FilmHandle, type FilmState } from './Film'
import Overview from './Overview'
import { copy, sectionName, slideTitle, type Lang } from './copy'
import { deckOf, loadCut, thumbFrame, thumbUrl, type Cut, type Deck } from './deck'
import { Notes, type SaveState } from './notes'
import { practiceLink } from '../lib/access'

const LANG = 'wn-practice.lang'
const MUTED = 'wn-practice.muted'
const AT = 'wn-practice.at'

function remembered<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}
function remember(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* the page works without it; it only forgets */
  }
}

/**
 * The practice page: the show as the client will click it, one click at a time, with room to write
 * what they will say. A click plays and then holds on its last frame until the next one, as the show
 * waits for the presenter. No voice-over and no subtitles are ever offered here — see deck.ts.
 */
export default function Practice() {
  const [lang, setLang] = useState<Lang>(() => remembered<Lang>(LANG, 'nl'))
  const t = copy[lang]
  const [cut, setCut] = useState<Cut | null>(null)
  const [failed, setFailed] = useState<'' | 'empty' | 'failed'>('')
  const deck = useMemo<Deck | null>(() => (cut ? deckOf(cut.states) : null), [cut])
  const [current, setCurrent] = useState(0)
  const [phase, setPhase] = useState<FilmState>('idle')
  const [time, setTime] = useState(0)
  const [overview, setOverview] = useState(false)
  const [muted, setMuted] = useState(() => remembered(MUTED, false))
  const [fullscreen, setFullscreen] = useState(false)
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const film = useRef<FilmHandle>(null)

  const notes = useMemo(() => new Notes(), [])
  const [, redraw] = useReducer((n: number) => n + 1, 0)
  useEffect(() => notes.subscribe(redraw), [notes])
  useEffect(() => {
    notes.load()
    const away = () => { if (document.visibilityState === 'hidden') notes.flush(true) }
    const leave = () => notes.flush(true)
    document.addEventListener('visibilitychange', away)
    window.addEventListener('pagehide', leave)
    return () => {
      document.removeEventListener('visibilitychange', away)
      window.removeEventListener('pagehide', leave)
    }
  }, [notes])

  useEffect(() => { remember(LANG, lang); document.documentElement.lang = lang }, [lang])
  useEffect(() => { remember(MUTED, muted) }, [muted])

  useEffect(() => {
    loadCut()
      .then((c) => (c ? setCut(c) : setFailed('empty')))
      .catch(() => setFailed('failed'))
  }, [])

  const states = cut?.states ?? []
  const state = states[current]
  const slide = deck?.slideOf[current]
  const click = slide ? slide.states.indexOf(current) : 0
  const next = states[current + 1]
  const nextSlide = deck?.slideOf[current + 1]

  // Where the page opens: the click in the address, else where this browser left off. It opens on
  // that click standing finished, and waits to be played.
  const opened = useRef(false)
  useEffect(() => {
    if (!cut || opened.current) return
    opened.current = true
    const wanted = decodeURIComponent(location.hash.slice(1)) || remembered<string>(AT, '')
    const i = Math.max(0, cut.states.findIndex((s) => s.key === wanted))
    const s = cut.states[i]
    setCurrent(i)
    setPhase('idle')
    setTimeout(() => film.current?.show(Math.max(s.start, s.end - 0.05), s.end), 0)
  }, [cut])

  useEffect(() => {
    if (!state) return
    const h = `#${state.key}`
    if (location.hash !== h) history.replaceState(null, '', h)
    remember(AT, state.key)
  }, [state])

  // --- moving through the show -------------------------------------------------------- //

  const go = useCallback((i: number) => {
    if (!cut) return
    const k = Math.max(0, Math.min(cut.states.length - 1, i))
    const s = cut.states[k]
    setCurrent(k)
    setOverview(false)
    setPhase('waiting')
    setStartedAt((at) => at ?? Date.now())
    film.current?.play(s.start, s.end)
  }, [cut])

  const goNext = () => { if (current < states.length - 1) go(current + 1) }
  const goPrev = () => { if (current > 0) go(current - 1) }
  const replay = () => go(current)
  const onFilm = () => {
    if (phase === 'idle' || phase === 'held') replay()
    else film.current?.toggle()
  }

  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else document.documentElement.requestFullscreen().catch(() => {})
  }
  useEffect(() => {
    const on = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', on)
    return () => document.removeEventListener('fullscreenchange', on)
  }, [])

  // The keys. A presenter clicker sends Page Down and Page Up, which move the show even while the notes
  // are being typed in; its play button sends F5, which would otherwise reload the page, and here opens
  // the overview. Everything else waits until the notes are left.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey) return
      const target = e.target as HTMLElement
      const typing = target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'
      if (e.key === 'PageDown') { e.preventDefault(); goNext(); return }
      if (e.key === 'PageUp') { e.preventDefault(); goPrev(); return }
      if (e.key === 'F5') { e.preventDefault(); setOverview((v) => !v); return }
      if (e.key === 'Escape') {
        if (overview) setOverview(false)
        else if (typing) target.blur()
        return
      }
      if (typing) return
      const k = e.key.toLowerCase()
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === ' ' || e.key === 'Enter') { e.preventDefault(); goNext() }
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); goPrev() }
      else if (k === 'r') replay()
      else if (k === 'o') setOverview((v) => !v)
      else if (k === 'f') toggleFullscreen()
      else if (k === 'm') setMuted((m) => !m)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const onTime = useCallback((s: number) => setTime(s), [])
  const onState = useCallback((s: FilmState) => setPhase(s), [])

  // --- the notes ---------------------------------------------------------------------- //

  const downloadNotes = () => {
    if (!cut || !deck) return
    const written = notes.written()
    const lines = [`${t.exportTitle} — ${new Date().toLocaleDateString(lang === 'nl' ? 'nl-NL' : 'en-GB', { day: 'numeric', month: 'long', year: 'numeric' })}`, '']
    for (const section of deck.sections) {
      const slides = section.slides.filter((sl) => sl.states.some((i) => written[cut.states[i].key]))
      if (!slides.length) continue
      lines.push(section.kind === 'chapter' ? `${t.chapter(section.number)} · ${sectionName(section.name)}` : t.moments[section.name] ?? section.name, '')
      for (const sl of slides) {
        lines.push(`  ${t.slideOf(sl.number, deck.slides.length)} — ${slideTitle(t, sl.title, section.kind === 'moment')}`)
        sl.states.forEach((i, n) => {
          const body = written[cut.states[i].key]
          if (!body) return
          lines.push(`    ${t.clickOf(n + 1, sl.states.length)}:`)
          for (const l of body.trim().split('\n')) lines.push(`      ${l}`)
        })
        lines.push('')
      }
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${t.exportTitle.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.txt`
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  }

  // --- the page ----------------------------------------------------------------------- //

  if (failed || !cut || !deck || !state || !slide) {
    return (
      <div className="practice">
        <Head t={t} lang={lang} onLang={setLang} />
        <div className="p-empty">{failed === 'empty' ? t.empty : failed === 'failed' ? t.failed : t.loading}</div>
      </div>
    )
  }

  const moment = slide.section.kind === 'moment'
  const title = slideTitle(t, slide.title, moment)
  const sectionLabel = moment ? t.moments[slide.section.name] ?? slide.section.name : `${t.chapter(slide.section.number)} · ${sectionName(slide.section.name)}`
  const progress = Math.max(0, Math.min(1, (time - state.start) / Math.max(0.001, state.end - state.start)))
  const held = phase === 'held'
  const last = current === states.length - 1
  const save: SaveState = notes.state

  return (
    <div className={`practice phase-${phase}`}>
      <Head t={t} lang={lang} onLang={setLang} created={cut.created}>
        <Clock t={t} startedAt={startedAt} onReset={() => setStartedAt(null)} />
        <button className="ghost" onClick={() => setOverview(true)} title={`${t.overview} (O)`}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><rect x="1" y="2" width="6" height="5" rx="1" /><rect x="9" y="2" width="6" height="5" rx="1" /><rect x="1" y="9" width="6" height="5" rx="1" /><rect x="9" y="9" width="6" height="5" rx="1" /></svg>
          {t.overview}
        </button>
        <Share t={t} />
        {(cut.audioUrl || cut.filmSound) && (
          <button className="ghost icon" onClick={() => setMuted((m) => !m)} title={`${muted ? t.unmute : t.mute} (M)`} aria-label={muted ? t.unmute : t.mute}>
            {muted ? (
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.5h3l4-3v11l-4-3h-3z" /><path d="M12 6.5l4 5M16 6.5l-4 5" /></svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.5h3l4-3v11l-4-3h-3z" /><path d="M12.5 6a4.5 4.5 0 0 1 0 6M14.5 4a7 7 0 0 1 0 10" /></svg>
            )}
          </button>
        )}
        <button className="ghost icon" onClick={toggleFullscreen} title={`${fullscreen ? t.exitFullscreen : t.fullscreen} (F)`} aria-label={fullscreen ? t.exitFullscreen : t.fullscreen}>
          {fullscreen ? (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3v4H3M11 3v4h4M7 15v-4H3M11 15v-4h4" /></svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7V3h4M15 7V3h-4M3 11v4h4M15 11v4h-4" /></svg>
          )}
        </button>
      </Head>

      <main className="p-main">
        <div className="p-stage">
          <Film ref={film} src={cut.videoUrl} audio={cut.audioUrl} filmSound={cut.filmSound} muted={muted} onTime={onTime} onState={onState} onClick={onFilm}>
            {phase === 'idle' && (
              <div className="film-veil">
                <button className="film-start" onClick={(e) => { e.stopPropagation(); replay() }}>
                  <svg width="22" height="22" viewBox="0 0 26 26" fill="currentColor" aria-hidden="true"><path d="M8 4.5v17l14-8.5z" /></svg>
                  {current === 0 ? t.start : t.startHint}
                </button>
              </div>
            )}
            {phase === 'waiting' && <div className="film-spinner" aria-label={t.loading} />}
            {phase === 'paused' && (
              <div className="film-paused" aria-label={t.paused}>
                <svg width="30" height="30" viewBox="0 0 26 26" fill="currentColor"><rect x="5" y="4" width="6" height="18" rx="1" /><rect x="15" y="4" width="6" height="18" rx="1" /></svg>
              </div>
            )}
          </Film>
          <div className="p-progress" aria-hidden="true"><span style={{ width: `${progress * 100}%` }} /></div>
          <SectionBar t={t} deck={deck} total={states.length} current={current} onJump={go} />
        </div>

        <div className="p-controls">
          <button className="p-prev" onClick={goPrev} disabled={current === 0} title={`${t.prev} (←)`}>
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12.5 4 6.5 10l6 6" /></svg>
            {t.prev}
          </button>

          <div className="p-now">
            <div className="p-section">{sectionLabel}</div>
            <div className="p-slide">{title}</div>
            <div className="p-click">
              <span className="p-dots" aria-hidden="true">
                {slide.states.map((i, n) => <i key={i} className={n < click ? 'past' : n === click ? 'now' : ''} />)}
              </span>
              <span>{t.clickOf(click + 1, slide.states.length)}</span>
              <span className="p-sep">·</span>
              <span>{t.slideOf(slide.number, deck.slides.length)}</span>
              <span className={`p-status ${phase}`}>
                {held ? (last ? t.end : t.ready) : phase === 'playing' ? t.playing : phase === 'paused' ? t.paused : phase === 'waiting' ? t.loading : ''}
              </span>
            </div>
          </div>

          <button className="p-replay ghost" onClick={replay} title={`${t.replay} (R)`}>
            <svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M4 10a6 6 0 1 0 2-4.5" /><path d="M4 3v3.5h3.5" /></svg>
            {t.replay}
          </button>
          <button className={`p-next${held && !last ? ' ready' : ''}`} onClick={goNext} disabled={last} title={`${t.next} (→)`}>
            {t.next}
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M7.5 4l6 6-6 6" /></svg>
          </button>
        </div>

        <div className="p-lower">
          <section className="p-notes">
            <header>
              <div>
                <h2>{t.notes}</h2>
                <p>{t.notesFor(title, t.clickOf(click + 1, slide.states.length))}</p>
              </div>
              <span className={`p-save ${save}`}>{save === 'saving' ? t.saving : save === 'saved' ? t.saved : save === 'local' ? t.local : ''}</span>
            </header>
            <textarea
              key={state.key}
              value={notes.get(state.key)}
              placeholder={t.placeholder}
              onChange={(e) => notes.set(state.key, state.slide, e.target.value)}
              onBlur={() => notes.flush()}
              spellCheck
            />
            <footer>
              <button className="ghost small" onClick={downloadNotes}>
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10" /></svg>
                {t.download}
              </button>
            </footer>
          </section>

          <aside className="p-upnext">
            <h2>{t.upNext}</h2>
            {next && nextSlide ? (
              <button className="p-upnext-card" onClick={goNext}>
                <span className={`p-thumb ${thumbFrame(next)}`}><img src={thumbUrl(cut, next)} alt="" /></span>
                <span className="p-upnext-where">
                  {nextSlide === slide ? t.sameSlide : nextSlide.section !== slide.section
                    ? nextSlide.section.kind === 'chapter' ? `${t.chapter(nextSlide.section.number)} · ${sectionName(nextSlide.section.name)}` : t.moments[nextSlide.section.name] ?? nextSlide.section.name
                    : t.slideOf(nextSlide.number, deck.slides.length)}
                </span>
                <b>{slideTitle(t, nextSlide.title, nextSlide.section.kind === 'moment')}</b>
                <span className="p-upnext-click">{t.clickOf(nextSlide.states.indexOf(current + 1) + 1, nextSlide.states.length)}</span>
                <span className={`p-upnext-note${notes.has(next.key) ? '' : ' none'}`}>{notes.get(next.key).trim() || t.noNote}</span>
              </button>
            ) : (
              <p className="p-upnext-end">{t.end}</p>
            )}
          </aside>
        </div>

        <p className="p-keys">{t.keys}</p>
      </main>

      {overview && (
        <Overview t={t} cut={cut} deck={deck} current={current} hasNote={(k) => notes.has(k)} onPick={go} onClose={() => setOverview(false)} />
      )}
    </div>
  )
}

function Head({ t, lang, onLang, created, children }: { t: (typeof copy)['nl']; lang: Lang; onLang(l: Lang): void; created?: string; children?: ReactNode }) {
  // the cut's own date, read as a calendar day wherever the page is opened
  const day = created ? new Date(`${created}T12:00:00`).toLocaleDateString(t.locale, { day: 'numeric', month: 'long', year: 'numeric' }) : ''
  // The tab carries the version too, so a bookmark or a row of tabs says which cut is open.
  useEffect(() => {
    document.title = day ? `${t.version(day)} · ${t.brand}` : `${t.tag} · ${t.brand}`
  }, [day, t])
  return (
    <header className="p-head">
      <div className="p-brand">
        <span className="p-mark">WN</span>
        <span>{t.brand}</span>
        <span className="p-tag">{t.tag}</span>
        {day && <span className="p-version" title={t.versionHint}>{t.version(day)}</span>}
      </div>
      <div className="spacer" />
      {children}
      <div className="p-lang" role="group" aria-label="Taal / language">
        {(['nl', 'en'] as const).map((l) => (
          <button key={l} className={l === lang ? 'on' : ''} onClick={() => onLang(l)}>{l.toUpperCase()}</button>
        ))}
      </div>
    </header>
  )
}

/**
 * The page's own secret link, to copy and send. It is always the practice key's link — never the review
 * key, even when the review key opened the page (see practiceLink).
 */
function Share({ t }: { t: (typeof copy)['nl'] }) {
  const [open, setOpen] = useState(false)
  const [link, setLink] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const field = useRef<HTMLInputElement>(null)
  useEffect(() => { practiceLink().then(setLink) }, [])
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [open])
  const copyLink = async () => {
    if (!link) return
    try {
      await navigator.clipboard.writeText(link)
    } catch {
      field.current?.select()
      document.execCommand('copy')
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }
  return (
    <div className="p-share" ref={box} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false) } }}>
      <button className="ghost" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6.5 9.5 9.5 6.5" /><path d="M7 4.5 8.5 3a2.8 2.8 0 0 1 4 4L11 8.5" /><path d="M9 11.5 7.5 13a2.8 2.8 0 0 1-4-4L5 7.5" /></svg>
        {t.share}
      </button>
      {open && (
        <div className="p-share-pop" role="dialog" aria-label={t.shareTitle}>
          <h3>{t.shareTitle}</h3>
          {link ? (
            <>
              <div className="p-share-row">
                <input ref={field} readOnly value={link} onFocus={(e) => e.target.select()} aria-label={t.shareTitle} />
                <button className={`p-copy${copied ? ' done' : ''}`} onClick={copyLink}>{copied ? `✓ ${t.copied}` : t.copy}</button>
              </div>
              <p>{t.shareHint}</p>
            </>
          ) : (
            <p>{t.shareNone}</p>
          )}
        </div>
      )}
    </div>
  )
}

/** Practice time, from the first click played; it says how long a run-through has taken. */
function Clock({ t, startedAt, onReset }: { t: (typeof copy)['nl']; startedAt: number | null; onReset(): void }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  const s = startedAt ? Math.max(0, Math.floor((now - startedAt) / 1000)) : 0
  const h = Math.floor(s / 3600)
  const text = `${h ? `${h}:` : ''}${String(Math.floor((s % 3600) / 60)).padStart(h ? 2 : 1, '0')}:${String(s % 60).padStart(2, '0')}`
  return (
    <div className="p-clock" title={t.timer}>
      <span className="p-clock-label">{t.timer}</span>
      <span className="p-clock-time">{text}</span>
      {startedAt && <button className="link" onClick={onReset} title={t.resetTimer} aria-label={t.resetTimer}>↺</button>}
    </div>
  )
}

/**
 * The evening as one strip under the film, a segment a chapter or moment, as wide as its clicks: where
 * the talk is, and a click away from the start of any part of it. A chapter carries its number and its
 * title, so the four read as what they are about; the moments between are too narrow for words.
 */
function SectionBar({ t, deck, total, current, onJump }: { t: (typeof copy)['nl']; deck: Deck; total: number; current: number; onJump(i: number): void }) {
  return (
    <div className="p-sections">
      {deck.sections.map((s) => {
        const on = current >= s.first && current <= s.last
        const name = s.kind === 'chapter' ? `${t.chapter(s.number)} · ${sectionName(s.name)}` : t.moments[s.name] ?? s.name
        return (
          <button
            key={s.index}
            className={`p-seg ${s.kind}${on ? ' on' : ''}`}
            style={{ left: `${(s.first / total) * 100}%`, width: `${((s.last - s.first + 1) / total) * 100}%` }}
            onClick={() => onJump(s.first)}
            title={name}
          >
            {s.kind === 'chapter' && (
              <>
                <b>{s.number}</b>
                <span>{sectionName(s.name)}</span>
              </>
            )}
          </button>
        )
      })}
      <i className="p-here" style={{ left: `${((current + 0.5) / total) * 100}%` }} />
    </div>
  )
}
