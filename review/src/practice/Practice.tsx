import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import Film, { type FilmHandle, type FilmState } from './Film'
import Overview from './Overview'
import { copy, sectionName, slideTitle, type Lang } from './copy'
import { deckOf, loadCut, thumbFrame, thumbUrl, type Cut, type Deck } from './deck'
import { Notes, type SaveState } from './notes'
import { Script } from './script'
import { wordDiff } from './diff'
import { practiceLink } from '../lib/access'
import { cardAt, cards as cardsOf } from './subtitles'

const LANG = 'wn-practice.lang'
const MUTED = 'wn-practice.muted'
// A new key since 15 October, when the subtitle went off by default: the autocue under the film carries the
// text now, and every browser that had been here had stored the old default.
const SUBS = 'wn-practice.subtitles-v2'
const CUE = 'wn-practice.autocue'
const CUE_PLACE = 'wn-practice.autocue-place'

/** The autocue text of one click while it is being edited: what it said as editing began, and the draft. */
interface Draft { key: string; slide: string; from: string; text: string }
/** The keys that move the show, held while the text is being edited. */
const NAV_KEYS = new Set(['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', ' ', 'Enter', 'r', 'R', 'o', 'O'])
/** The same text, give or take white space. */
const same = (a: string, b: string) => a.replace(/\s+/g, ' ').trim() === b.replace(/\s+/g, ' ').trim()
/** A moment as the page names it: day, month and time. */
const stamp = (t: (typeof copy)['nl'], iso: string) => new Date(iso).toLocaleString(t.locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

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
  const [subs, setSubs] = useState(() => remembered(SUBS, false))
  const [cueOn, setCueOn] = useState(() => remembered(CUE, true))
  const cueFilm = useRef<HTMLVideoElement>(null)
  const [fullscreen, setFullscreen] = useState(false)
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const film = useRef<FilmHandle>(null)

  const notes = useMemo(() => new Notes(), [])
  const script = useMemo(() => new Script(), [])
  const [changesOpen, setChangesOpen] = useState(false)
  // The autocue text is edited behind a lock: "Bewerk deze tekst" opens a draft, the rest of the page stands
  // still under a veil until it is saved or cancelled, and only the save writes the change (script.ts).
  const [edit, setEdit] = useState<Draft | null>(null)
  const editing = useRef<Draft | null>(null)
  editing.current = edit
  const [nudged, setNudged] = useState(0)
  const nudge = () => setNudged((n) => n + 1)
  const [, redraw] = useReducer((n: number) => n + 1, 0)
  useEffect(() => notes.subscribe(redraw), [notes])
  useEffect(() => script.subscribe(redraw), [script])
  useEffect(() => {
    notes.load()
    script.load()
    const away = () => { if (document.visibilityState === 'hidden') { notes.flush(true); script.flush(true) } }
    const leave = () => { notes.flush(true); script.flush(true) }
    document.addEventListener('visibilitychange', away)
    window.addEventListener('pagehide', leave)
    return () => {
      document.removeEventListener('visibilitychange', away)
      window.removeEventListener('pagehide', leave)
    }
  }, [notes])

  // A draft not yet saved is asked about before the page is left.
  useEffect(() => {
    if (!edit || same(edit.text, edit.from)) return
    const ask = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', ask)
    return () => window.removeEventListener('beforeunload', ask)
  }, [edit])

  useEffect(() => { remember(LANG, lang); document.documentElement.lang = lang }, [lang])
  useEffect(() => { remember(MUTED, muted) }, [muted])
  useEffect(() => { remember(SUBS, subs) }, [subs])
  useEffect(() => { remember(CUE, cueOn) }, [cueOn])

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

  // The page always opens on the opening scene, whatever the address or the last visit said: a
  // rehearsal starts at the top (asked 2 October). It opens on that click standing finished, and
  // waits to be played.
  const opened = useRef(false)
  useEffect(() => {
    if (!cut || opened.current) return
    opened.current = true
    const i = 0
    const s = cut.states[i]
    setCurrent(i)
    setPhase('idle')
    setTimeout(() => film.current?.show(Math.max(s.start, s.end - 0.05), s.end), 0)
  }, [cut])

  useEffect(() => {
    if (!state) return
    const h = `#${state.key}`
    if (location.hash !== h) history.replaceState(null, '', h)
  }, [state])

  // --- moving through the show -------------------------------------------------------- //

  const go = useCallback((i: number) => {
    if (!cut) return
    if (editing.current) { nudge(); return }
    const k = Math.max(0, Math.min(cut.states.length - 1, i))
    const s = cut.states[k]
    setCurrent(k)
    setOverview(false)
    setChangesOpen(false)
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
      // While the text is being edited nothing else moves: the clicker and the keys only say to save first,
      // and Esc leaves only a draft that has not been changed.
      const draft = editing.current
      if (draft) {
        if (e.key === 'Escape') {
          e.preventDefault()
          if (same(draft.text, draft.from)) setEdit(null)
          else nudge()
        } else if (e.key === 'PageDown' || e.key === 'PageUp' || e.key === 'F5' || (!typing && NAV_KEYS.has(e.key))) {
          e.preventDefault()
          nudge()
        }
        return
      }
      if (e.key === 'PageDown') { e.preventDefault(); goNext(); return }
      if (e.key === 'PageUp') { e.preventDefault(); goPrev(); return }
      if (e.key === 'F5') { e.preventDefault(); setOverview((v) => !v); return }
      if (e.key === 'Escape') {
        if (overview) setOverview(false)
        else if (changesOpen) setChangesOpen(false)
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
      else if (k === 's') setSubs((v) => !v)
      else if (k === 'a') setCueOn((v) => !v)
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
  // The line said over this click, as the show sets it on the wall: card by card, word by word, in step
  // with the film. Waiting for a click to start shows nothing; a click held still keeps its last card.
  const line = cut.lines[state.key] ?? ''
  const card = subs && line ? cardAt(cardsOf(line), phase === 'waiting' ? -1 : time - state.start) : null
  const held = phase === 'held'
  const last = current === states.length - 1
  const save: SaveState = notes.state
  const cueShown = cueOn && !!cut.autocueUrl
  const changes = script.changes()
  const adoptions = script.adoptions()

  return (
    <div className={`practice phase-${phase}`}>
      <Head t={t} lang={lang} onLang={setLang} created={cut.created}>
        <Clock t={t} startedAt={startedAt} onReset={() => setStartedAt(null)} />
        <button className="ghost" onClick={() => setOverview(true)} title={`${t.overview} (O)`}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><rect x="1" y="2" width="6" height="5" rx="1" /><rect x="9" y="2" width="6" height="5" rx="1" /><rect x="1" y="9" width="6" height="5" rx="1" /><rect x="9" y="9" width="6" height="5" rx="1" /></svg>
          {t.overview}
        </button>
        <button className={`ghost${changes.length ? ' p-changes-btn' : ''}`} onClick={() => setChangesOpen(true)} title={t.changesHint}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 12.5 11 4.5l1.5 1.5-8 8H3z" /><path d="M9.5 6 11 7.5" /></svg>
          {t.changes}
          {changes.length > 0 && <span className="p-count">{changes.length}</span>}
          {adoptions.length > 0 && <span className="p-count adopted" title={t.adoptedCount(adoptions.length)}>✓ {adoptions.length}</span>}
        </button>
        <Sheets t={t} cut={cut} />
        {cut.autocueUrl && (
          <button className={`ghost${cueOn ? '' : ' off'}`} onClick={() => setCueOn((v) => !v)} title={`${cueOn ? t.hideAutocue : t.showAutocue} (A)`} aria-pressed={cueOn}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden="true"><rect x="1.5" y="2.5" width="13" height="9" rx="1.5" /><path d="M4 5.5h8M4 8.5h5M6 14h4" /></svg>
            {t.autocue}
          </button>
        )}
        <Share t={t} />
        <button className={`ghost icon${subs ? '' : ' off'}`} onClick={() => setSubs((v) => !v)} title={`${subs ? t.hideLine : t.showLine} (S)`} aria-label={subs ? t.hideLine : t.showLine} aria-pressed={subs}>
          <svg width="20" height="18" viewBox="0 0 20 18" aria-hidden="true">
            <rect x="1.5" y="3" width="17" height="12" rx="2.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <text x="10" y="12.2" textAnchor="middle" fontSize="7" fontWeight="700" fill="currentColor" fontFamily="system-ui, sans-serif">CC</text>
            {!subs && <path d="M3 16.5L17 1.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />}
          </svg>
        </button>
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
          <Film ref={film} src={cut.videoUrl} audio={cut.audioUrl} filmSound={cut.filmSound} muted={muted} onTime={onTime} onState={onState} onClick={onFilm}
            follow={cueFilm} followKey={cueShown ? cut.autocueUrl ?? '' : ''}>
            {phase === 'idle' && (
              <div className="film-veil">
                <button className="film-start" onClick={(e) => { e.stopPropagation(); replay() }}>
                  <svg width="22" height="22" viewBox="0 0 26 26" fill="currentColor" aria-hidden="true"><path d="M8 4.5v17l14-8.5z" /></svg>
                  {current === 0 ? t.start : t.startHint}
                </button>
              </div>
            )}
            {card && (
              // At the foot of the slide's half of the film, where the show sets its subtitles. Every word
              // of the card is laid out from its first frame, those not yet said invisible, so nothing reflows.
              <div className="film-subtitle" aria-live="off">
                <div className="film-subtitle-card">
                  {(() => {
                    let k = 0
                    return card.lines.map((l, j) => (
                      <div key={j}>
                        {l.split(' ').filter(Boolean).map((w, i, ws) => {
                          const at = card.words[k++] ?? card.at
                          const o = Math.max(0, Math.min(1, (time - state.start - at) / 0.2))
                          return <span key={i} style={{ opacity: phase === 'idle' || phase === 'held' ? 1 : o }}>{w}{i < ws.length - 1 ? ' ' : ''}</span>
                        })}
                      </div>
                    ))
                  })()}
                </div>
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

        {cueShown && <FloatingCue t={t} src={cut.autocueUrl!} video={cueFilm} onHide={() => setCueOn(false)} />}

        <div className="p-lower">
          <div className="p-left">
          <ScriptPanel t={t} script={script} stateKey={state.key} title={title}
            click={t.clickOf(click + 1, slide.states.length)} onChanges={() => setChangesOpen(true)} count={changes.length}
            draft={edit?.key === state.key ? edit.text : null} nudged={nudged}
            onEdit={(text) => setEdit({ key: state.key, slide: state.slide, from: script.text(state.key), text: text ?? script.text(state.key) })}
            onDraft={(text) => setEdit((d) => (d ? { ...d, text } : d))}
            onSave={() => {
              if (edit && !same(edit.text, edit.from)) { script.set(edit.key, edit.slide, edit.text); script.flush() }
              setEdit(null)
            }}
            onCancel={() => setEdit(null)} />
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
          </div>

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

      {edit && <div className="p-edit-veil" onPointerDown={(e) => { e.preventDefault(); nudge() }} title={t.saveFirst} />}
      {overview && (
        <Overview t={t} cut={cut} deck={deck} current={current} hasNote={(k) => notes.has(k)} isChanged={(k) => script.changed(k)} isAdopted={(k) => !!script.adoption(k)} onPick={go} onClose={() => setOverview(false)} />
      )}
      {changesOpen && (
        <Changes t={t} cut={cut} deck={deck} script={script} onGo={go} onClose={() => setChangesOpen(false)} />
      )}
    </div>
  )
}

/** A change as it reads: the old words struck through where they went, the new ones marked where they came. */
function Diff({ before, after }: { before: string; after: string }) {
  return (
    <p className="p-diff">
      {wordDiff(before, after).map((piece, i) => {
        const gap = i > 0 ? ' ' : ''
        return piece.kind === 'same' ? <span key={i}>{gap}{piece.text}</span>
          : piece.kind === 'out' ? <span key={i}>{gap}<del>{piece.text}</del></span>
          : <span key={i}>{gap}<ins>{piece.text}</ins></span>
      })}
    </p>
  )
}

/**
 * The autocue's text for this click — above the speaker notes, since it is what is said. Who reads it and any stage
 * direction stand over it; once the text differs from what it was, the panel says so, shows the change against the
 * original, and offers to set it back. Every edit is kept with the text it was made from (script.ts). Once the show
 * has taken the edit in, the panel says that instead, in green, with what the click said before and when.
 *
 * **It is read only until "Bewerk deze tekst" is clicked**, so the text is never changed by a stray keystroke while
 * the show is being clicked through. Editing is a mode: the field turns to paper, the rest of the page stands still
 * under a veil, and nothing is written until "Opslaan" — "Annuleren" leaves the text as it was.
 */
function ScriptPanel({ t, script, stateKey, title, click, onChanges, count, draft, nudged, onEdit, onDraft, onSave, onCancel }: {
  t: (typeof copy)['nl']; script: Script; stateKey: string; title: string; click: string; onChanges(): void; count: number
  draft: string | null; nudged: number; onEdit(text?: string): void; onDraft(text: string): void; onSave(): void; onCancel(): void
}) {
  const editing = draft !== null
  const text = editing ? draft : script.text(stateKey)
  const original = script.original(stateKey)
  const changed = editing ? !same(draft, original) : script.changed(stateKey)
  const adoption = editing ? undefined : script.adoption(stateKey)
  // taken in other than as typed, and the text left as it was: handled rather than taken over
  const handled = !!adoption && same(adoption.from, text)
  const who = script.speaker(stateKey)
  const directions = script.directions(stateKey)
  const save = script.state
  const box = useRef<HTMLElement>(null)
  const field = useRef<HTMLTextAreaElement>(null)

  // Into edit mode: the panel comes to the middle of the window and the caret to the end of the text.
  useEffect(() => {
    if (!editing) return
    box.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    const f = field.current
    if (f) { f.focus({ preventScroll: true }); f.setSelectionRange(f.value.length, f.value.length) }
  }, [editing])

  // A key or a click elsewhere while editing shakes the bar that says how to get out.
  const bar = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!nudged || !bar.current) return
    bar.current.classList.remove('nudge')
    void bar.current.offsetWidth
    bar.current.classList.add('nudge')
  }, [nudged])

  return (
    <section ref={box} className={`p-script${changed ? ' changed' : ''}${adoption ? ' adopted' : ''}${editing ? ' editing' : ''}`} title={editing ? undefined : t.scriptAbout}>
      {editing && (
        <div className="p-edit-bar" ref={bar}>
          <span className="p-edit-tag">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 12.5 11 4.5l1.5 1.5-8 8H3z" /><path d="M9.5 6 11 7.5" /></svg>
            {t.editing}
          </span>
          <span className="p-edit-hint">{t.editingHint}</span>
        </div>
      )}
      <header>
        <div>
          <h2>
            {t.script}
            {changed && <span className="p-badge">{t.changed}</span>}
            {adoption && <span className="p-badge adopted">✓ {handled ? t.handled : t.adopted}</span>}
          </h2>
          <p>
            {t.scriptFor(title, click)}
            {who && <span className={`p-who${script.guest(stateKey) ? ' guest' : ''}`}>{who}</span>}
          </p>
        </div>
        {editing ? null : (
          <div className="p-script-side">
            <span className={`p-save ${save}`}>{save === 'saving' ? t.saving : save === 'saved' ? t.saved : save === 'local' ? t.local : ''}</span>
            <button className="p-edit-open" onClick={() => onEdit()}>
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 12.5 11 4.5l1.5 1.5-8 8H3z" /><path d="M9.5 6 11 7.5" /></svg>
              {t.editText}
            </button>
          </div>
        )}
      </header>
      {directions.length > 0 && (
        <div className="p-regie">
          <b>{t.regie}</b>
          {directions.map((d, i) => <span key={i}>{d}</span>)}
        </div>
      )}
      {editing ? (
        <textarea
          ref={field}
          value={draft}
          placeholder={t.scriptEmptyEdit}
          onChange={(e) => onDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); onSave() } }}
          spellCheck
          lang="nl"
        />
      ) : (
        <div className={`p-script-text${text.trim() ? '' : ' empty'}`}>{text.trim() ? text : t.scriptEmpty}</div>
      )}
      {changed && (
        <div className="p-was">
          <div className="p-was-head">
            <span>{t.was}</span>
            <button className="link" onClick={() => (editing ? onDraft(original) : onEdit(original))}>{t.restore}</button>
          </div>
          <Diff before={original} after={text} />
          {!editing && script.moved(stateKey) && <p className="p-moved">{t.moved}</p>}
        </div>
      )}
      {adoption && (
        <div className="p-was adopted">
          <div className="p-was-head">
            <span>{(handled ? t.handledIn : t.adoptedIn)(stamp(t, adoption.at))}</span>
          </div>
          {!handled && <Diff before={adoption.from} after={text} />}
          {adoption.note && <p className="p-adopted-note">{adoption.note}</p>}
        </div>
      )}
      <footer>
        {editing ? (
          <>
            <span className="p-edit-keys">{t.saveKeys}</span>
            <button className="ghost small" onClick={onCancel}>{t.cancel}</button>
            <button className="p-edit-save" onClick={onSave}>
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 8.5 6.5 12 13 4.5" /></svg>
              {t.save}
            </button>
          </>
        ) : (
          <button className="ghost small" onClick={onChanges}>
            {t.changesTitle}{count > 0 ? ` · ${count}` : ''}
          </button>
        )}
      </footer>
    </section>
  )
}

/**
 * Every click whose text was changed here, in the order of the show: where it is, the change against what it said
 * before, when, and a way to go there or set it back — so what was changed, on which click, can be read off one list.
 * A click the film on the page does not have (a later version's) is listed apart, by its key.
 */
function Changes({ t, cut, deck, script, onGo, onClose }: {
  t: (typeof copy)['nl']; cut: Cut; deck: Deck; script: Script; onGo(i: number): void; onClose(): void
}) {
  const index = new Map(cut.states.map((s, i) => [s.key, i]))
  const changes = script.changes()
  const inOrder = <T extends { key: string }>(list: T[]) =>
    [...list.filter((c) => index.has(c.key)).sort((a, b) => index.get(a.key)! - index.get(b.key)!), ...list.filter((c) => !index.has(c.key))]
  const placed = changes.filter((c) => index.has(c.key)).sort((a, b) => index.get(a.key)! - index.get(b.key)!)
  const elsewhere = changes.filter((c) => !index.has(c.key))
  const adoptions = inOrder(script.adoptions())
  const when = (iso: string) => stamp(t, iso)
  const where = (key: string) => {
    const i = index.get(key)
    if (i == null) return { section: t.elsewhere, slide: key, click: '' }
    const sl = deck.slideOf[i]
    const moment = sl.section.kind === 'moment'
    return {
      section: moment ? t.moments[sl.section.name] ?? sl.section.name : `${t.chapter(sl.section.number)} · ${sectionName(sl.section.name)}`,
      slide: `${t.slideOf(sl.number, deck.slides.length)} — ${slideTitle(t, sl.title, moment)}`,
      click: t.clickOf(sl.states.indexOf(i) + 1, sl.states.length),
    }
  }
  const download = () => {
    const lines = [`${t.changesTitle} — ${new Date().toLocaleDateString(t.locale, { day: 'numeric', month: 'long', year: 'numeric' })}`, '']
    for (const c of [...placed, ...elsewhere]) {
      const w = where(c.key)
      lines.push(`${w.section}`, `  ${w.slide}${w.click ? `, ${w.click}` : ''}  (${c.key})`, `  ${t.was}:   ${c.original.replace(/\s+/g, ' ').trim() || '—'}`, `  ${t.becomes}: ${c.text.replace(/\s+/g, ' ').trim() || '—'}`, '')
    }
    if (adoptions.length) lines.push(`${t.adoptedTitle}`, '')
    for (const a of adoptions) {
      const w = where(a.key)
      lines.push(`${w.section}`, `  ${w.slide}${w.click ? `, ${w.click}` : ''}  (${a.key}, ${t.adoptedAt(when(a.at))})`, `  ${t.was}:   ${a.from.replace(/\s+/g, ' ').trim() || '—'}`, `  ${t.becomes}: ${a.text.replace(/\s+/g, ' ').trim() || '—'}`, ...(a.note ? [`  ${a.note}`] : []), '')
    }
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' }))
    a.download = `${t.changesTitle.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.txt`
    a.click()
    setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  }
  const item = (c: (typeof changes)[number]) => {
    const w = where(c.key)
    const i = index.get(c.key)
    return (
      <li key={c.key} className="ch-item">
        <div className="ch-where">
          <span className="ch-section">{w.section}</span>
          <b>{w.slide}</b>
          {w.click && <span>{w.click}</span>}
          <span className="ch-when">{t.changedAt(when(c.updatedAt))}</span>
        </div>
        <Diff before={c.original} after={c.text} />
        {script.moved(c.key) && <p className="p-moved">{t.moved}</p>}
        <div className="ch-actions">
          {i != null && <button className="ghost small" onClick={() => onGo(i)}>{t.goTo}</button>}
          <button className="ghost small" onClick={() => script.revert(c.key, c.slide)}>{t.restore}</button>
        </div>
      </li>
    )
  }
  const taken = (a: (typeof adoptions)[number]) => {
    const w = where(a.key)
    const i = index.get(a.key)
    const handled = same(a.from, a.text)
    return (
      <li key={a.key} className="ch-item adopted">
        <div className="ch-where">
          <span className="ch-section">{w.section}</span>
          <b>{w.slide}</b>
          {w.click && <span>{w.click}</span>}
          <span className="p-badge adopted">✓ {handled ? t.handled : t.adopted}</span>
          <span className="ch-when">{t.adoptedAt(when(a.at))}</span>
        </div>
        {!handled && <Diff before={a.from} after={a.text} />}
        {a.note && <p className="p-adopted-note">{a.note}</p>}
        {i != null && <div className="ch-actions"><button className="ghost small" onClick={() => onGo(i)}>{t.goTo}</button></div>}
      </li>
    )
  }
  return (
    <div className="changes" role="dialog" aria-modal="true" aria-label={t.changesTitle}>
      <div className="changes-head">
        <div>
          <h2>{t.changesTitle}{changes.length > 0 && <span className="p-count">{changes.length}</span>}</h2>
          <p>{t.changesHint}</p>
        </div>
        <div className="changes-tools">
          {changes.length + adoptions.length > 0 && <button className="ghost" onClick={download}>{t.downloadChanges}</button>}
          <button className="ghost big" onClick={onClose}>{t.close} <kbd>Esc</kbd></button>
        </div>
      </div>
      <div className="changes-list">
        {!changes.length && <p className="ch-none">{adoptions.length ? t.noOpenChanges : t.noChanges}</p>}
        <ol>{placed.map(item)}</ol>
        {elsewhere.length > 0 && <ol className="ch-elsewhere">{elsewhere.map(item)}</ol>}
        {adoptions.length > 0 && (
          <section className="ch-adopted">
            <h3>{t.adoptedTitle}<span className="p-count adopted">✓ {adoptions.length}</span></h3>
            <p>{t.adoptedHint}</p>
            <ol>{adoptions.map(taken)}</ol>
          </section>
        )}
      </div>
    </div>
  )
}

/**
 * Where the autocue stands: a row — top, middle, bottom — and a column — left, centre, right — of the window. The
 * corners, the middle of each edge, and the middle (asked 15 October: "centre bottom, or centre right").
 */
type Spot = `${'t' | 'm' | 'b'}${'l' | 'c' | 'r'}`
const SPOTS: Spot[] = ['tl', 'tc', 'tr', 'ml', 'mc', 'mr', 'bl', 'bc', 'br']
interface CuePlace { corner: Spot; width: number }

/** Room kept clear between the panel and the window's edge; the bar it is dragged by; its smallest width. */
const EDGE = 16
const BAR = 26
const SMALLEST = 260

/**
 * The autocue as the presenter sees it, filmed on the same run — it follows the film frame for frame, so the text
 * runs on as the click plays and KLIK stands at its end — in a panel floating over the page (asked 15 October).
 *
 * It stands in a corner of the window, or at the middle of an edge, or in the middle — a 3 by 3 grid of places:
 * dragged by its bar it goes where it is taken, and let go it settles into the place nearest to where it was left.
 * The grip on its inner corner resizes it, at the film's 16:9, about the place it stands in: a corner stays put, and
 * on the middle of an edge it grows out both ways, so it stays centred. **It is always wholly in the window**: its size is held between [SMALLEST] and what the window has
 * room for, the top corners keep below the page's head, and a window made smaller shrinks it with it. Its corner and
 * width are remembered per browser.
 */
function FloatingCue({ t, src, video, onHide }: { t: (typeof copy)['nl']; src: string; video: React.RefObject<HTMLVideoElement>; onHide(): void }) {
  const [place, setPlace] = useState<CuePlace>(() => {
    const p = remembered<Partial<CuePlace>>(CUE_PLACE, {})
    return { corner: SPOTS.includes(p.corner as Spot) ? p.corner as Spot : 'br', width: typeof p.width === 'number' ? p.width : 520 }
  })
  useEffect(() => { remember(CUE_PLACE, place) }, [place])
  const [view, setView] = useState({ w: window.innerWidth, h: window.innerHeight })
  useEffect(() => {
    const on = () => setView({ w: window.innerWidth, h: window.innerHeight })
    window.addEventListener('resize', on)
    document.addEventListener('fullscreenchange', on)
    return () => { window.removeEventListener('resize', on); document.removeEventListener('fullscreenchange', on) }
  }, [])
  // The top corners stand below the head, which stays on the page as it scrolls.
  const [head, setHead] = useState(64)
  useEffect(() => { setHead(Math.ceil(document.querySelector('.p-head')?.getBoundingClientRect().bottom ?? 64)) }, [view])
  const top = head + EDGE / 2

  const widest = Math.max(SMALLEST, Math.min(view.w - 2 * EDGE, (view.h - top - EDGE - BAR) * 16 / 9))
  const fit = (w: number) => Math.round(Math.max(SMALLEST, Math.min(widest, w)))
  const width = fit(place.width)
  const height = Math.round(width * 9 / 16) + BAR
  const at = (c: Spot) => ({
    left: c[1] === 'l' ? EDGE : c[1] === 'c' ? Math.round((view.w - width) / 2) : view.w - EDGE - width,
    // the middle row is the middle of the room below the head
    top: c[0] === 't' ? top : c[0] === 'm' ? Math.round(top + (view.h - EDGE - top - height) / 2) : view.h - EDGE - height,
  })
  const clampTo = (x: number, y: number) => ({
    left: Math.max(EDGE, Math.min(view.w - EDGE - width, x)),
    top: Math.max(top, Math.min(view.h - EDGE - height, y)),
  })

  // While dragged: where it is, and where the pointer took hold of it.
  const [dragged, setDragged] = useState<{ left: number; top: number } | null>(null)
  const hold = useRef({ dx: 0, dy: 0 })
  const startDrag = (e: React.PointerEvent<HTMLDivElement>) => {
    if ((e.target as HTMLElement).closest('button')) return
    const here = at(place.corner)
    hold.current = { dx: e.clientX - here.left, dy: e.clientY - here.top }
    e.currentTarget.setPointerCapture(e.pointerId)
    setDragged(here)
  }
  const moveDrag = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragged) return
    setDragged(clampTo(e.clientX - hold.current.dx, e.clientY - hold.current.dy))
  }
  const endDrag = () => {
    if (!dragged) return
    // the place nearest to where it was let go
    const d = dragged
    const nearest = SPOTS.reduce((best, s) => {
      const p = at(s), q = at(best)
      return Math.hypot(p.left - d.left, p.top - d.top) < Math.hypot(q.left - d.left, q.top - d.top) ? s : best
    })
    setPlace((p) => ({ ...p, corner: nearest }))
    setDragged(null)
  }

  // Resized from its inner corner, about the corner it stands in: the pointer's move along whichever axis it moves
  // most, read as a change of width.
  const grip = useRef<{ x: number; y: number; width: number } | null>(null)
  const [sizing, setSizing] = useState(false)
  const startSize = (e: React.PointerEvent<HTMLSpanElement>) => {
    e.stopPropagation()
    grip.current = { x: e.clientX, y: e.clientY, width }
    e.currentTarget.setPointerCapture(e.pointerId)
    setSizing(true)
  }
  const moveSize = (e: React.PointerEvent<HTMLSpanElement>) => {
    const g = grip.current
    if (!g) return
    // about a corner the grip's move is the change in size; about a middle the panel grows out both ways, so twice it
    const gx = ({ l: 1, c: 2, r: -1 })[place.corner[1] as 'l' | 'c' | 'r'] * (e.clientX - g.x)
    const gy = ({ t: 1, m: 2, b: -1 })[place.corner[0] as 't' | 'm' | 'b'] * (e.clientY - g.y) * 16 / 9
    setPlace((p) => ({ ...p, width: fit(g.width + (Math.abs(gx) > Math.abs(gy) ? gx : gy)) }))
  }
  const endSize = () => { grip.current = null; setSizing(false) }

  const pos = dragged ?? at(place.corner)
  // the grip on the corner facing the middle of the window: the foot unless it stands at the bottom, the right unless
  // it stands at the right
  const inner = `${place.corner[0] === 'b' ? 't' : 'b'}${place.corner[1] === 'r' ? 'l' : 'r'}`
  return (
    <section
      className={`p-cue-float${dragged ? ' dragging' : ''}${dragged || sizing ? '' : ' settled'}`}
      style={{ left: pos.left, top: pos.top, width, height }}
      title={t.autocueAbout}
    >
      <div className="p-cue-bar" onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={endDrag} onPointerCancel={endDrag}>
        <span className="p-cue-name">{t.autocue}</span>
        <span className="p-cue-hint">{t.autocueMove}</span>
        <button onClick={onHide} title={`${t.hideAutocue} (A)`} aria-label={t.hideAutocue}>×</button>
      </div>
      <video ref={video} src={src} preload="auto" playsInline muted controls={false} disablePictureInPicture />
      <span className={`p-cue-grip ${inner}`} onPointerDown={startSize} onPointerMove={moveSize} onPointerUp={endSize} onPointerCancel={endSize}
        title={t.autocueResize} aria-hidden="true" />
    </section>
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
/**
 * The speaker's sheets of the cut on the page, as PDFs to download: the extended one (the talk as it
 * is given) and the standard one (the voice-over script). A popover like Share's; offered only when the
 * cut lists its sheets in `practice.json`.
 */
function Sheets({ t, cut }: { t: (typeof copy)['nl']; cut: Cut }) {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [open])
  if (!cut.sheets.length) return null
  const day = new Date(`${cut.created}T12:00:00`).toLocaleDateString(t.locale, { day: 'numeric', month: 'long', year: 'numeric' })
  return (
    <div className="p-share" ref={box} onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); setOpen(false) } }}>
      <button className="ghost" onClick={() => setOpen((v) => !v)} aria-expanded={open} title={t.sheetsTitle}>
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10" /></svg>
        {t.sheets}
      </button>
      {open && (
        <div className="p-share-pop" role="dialog" aria-label={t.sheetsTitle}>
          <h3>{t.sheetsTitle}</h3>
          <div className="p-sheets">
            {cut.sheets.map((x) => (
              <a key={x.key} className="p-sheet" href={x.url} download={`WN sprekersnotities ${cut.created} ${x.key}.pdf`} onClick={() => setOpen(false)}>
                <b>{t.sheet[x.key] ?? x.key}</b>
                <span>{t.sheetAbout[x.key] ?? ''}</span>
              </a>
            ))}
          </div>
          <p>{t.sheetsHint(day)}</p>
        </div>
      )}
    </div>
  )
}

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
