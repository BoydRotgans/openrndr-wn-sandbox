import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Header, { type Update } from './components/Header'
import { type Counts } from './components/Minimap'
import Player, { type Dot, type PlayerHandle } from './components/Player'
import StateSeeker from './components/StateSeeker'
import NoteComposer from './components/NoteComposer'
import CopyKey from './components/CopyKey'
import Toasts, { type Toast } from './components/Toasts'
import { loadWaveform, type Waveform } from './lib/waveform'
import Thread, { VoiceOver } from './components/Thread'
import Timeline, { type NoteMark } from './components/Timeline'
import { NameDialog, NewReleaseDialog } from './components/Dialogs'
import General, { GENERAL } from './components/General'
import TasksTab from './components/TasksTab'
import { commentsCsv, download, formatTime } from './lib/csv'
import { prefs, seenKey } from './lib/prefs'
import { createStore } from './lib/store'
import type { Comment, CommentKind, CommentTopic, NewReleaseInput, Release, StateInfo } from './lib/types'

/** Seconds a pinned comment stays on the picture from its frame. */
const PIN_HOLD = 3

/** The state under the playhead: the last one whose start is at or before `t`. */
function stateAt(states: StateInfo[], t: number): number {
  let lo = 0
  let hi = states.length - 1
  if (hi < 0) return -1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (states[mid].start <= t + 1e-6) lo = mid
    else hi = mid - 1
  }
  return lo
}

function hashFor(release: Release | null, state: StateInfo | null) {
  return release ? `#${release.slug}${state ? `/${state.key}` : ''}` : ''
}

export default function App() {
  const store = useMemo(createStore, [])
  const player = useRef<PlayerHandle>(null)

  const [releases, setReleases] = useState<Release[]>([])
  const [releaseId, setReleaseId] = useState('')
  const [comments, setComments] = useState<Comment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [time, setTime] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [loop, setLoop] = useState(prefs.getLoop())

  const [name, setName] = useState(prefs.getName())
  const [seen, setSeen] = useState(prefs.getSeen())
  const [askName, setAskName] = useState<null | { then?: (name: string) => void }>(null)
  const [newRelease, setNewRelease] = useState(false)
  const [general, setGeneral] = useState(false)
  const [tasks, setTasks] = useState(false)
  // a pin taken for the next comment: the frame, and the spot once the picture is clicked
  const [pin, setPin] = useState<{ at: number; x: number | null; y: number | null } | null>(null)
  // the comment tool is armed for one note and disarms once it is placed or cancelled
  const [commenting, setCommenting] = useState(false)
  /** Which soundtrack is playing: the film's own, or another mix beside it (see Manifest.audio). */
  const [track, setTrack] = useState(prefs.getTrack())
  /** Which subtitle track is being read and edited: the default line, or the extended one. */
  const [voiceTrack, setVoiceTrack] = useState(prefs.getVoiceTrack())
  const [waveform, setWaveform] = useState<Waveform | null>(null)
  const [focus, setFocus] = useState<{ id: string; n: number } | null>(null)
  const [toasts, setToasts] = useState<Toast[]>([])
  const [ring, setRing] = useState(0)
  // the comment ids already seen, so a refresh can tell what is new; null until the first load
  const known = useRef<Set<string> | null>(null)

  const release = releases.find((r) => r.id === releaseId) ?? null
  const states = release?.manifest.states ?? []
  const fps = release?.manifest.fps ?? 60
  const current = stateAt(states, time)
  const state = current >= 0 ? states[current] : null

  // --- data ------------------------------------------------------------------------- //

  const refresh = useCallback(async () => {
    try {
      const [r, c] = await Promise.all([store.listReleases(), store.listComments()])
      setReleases(r)
      setComments(c)
      setError('')
    } catch (e) {
      setError(`Could not load: ${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [store])

  useEffect(() => {
    refresh()
    return store.subscribe(refresh)
  }, [store, refresh])

  // the release: the one in the address, else the newest
  useEffect(() => {
    if (!releases.length || releaseId) return
    const slug = location.hash.slice(1).split('/')[0]
    const wanted = releases.find((r) => r.slug === slug) ?? releases[0]
    setReleaseId(wanted.id)
  }, [releases, releaseId])

  // a state named in the address opens there, paused
  const openedHash = useRef(false)
  useEffect(() => {
    if (!release || openedHash.current) return
    openedHash.current = true
    const key = location.hash.slice(1).split('/')[1]
    const s = key ? states.find((x) => x.key === key) : null
    if (s) {
      setTime(s.start)
      // the video element may not have metadata yet; seeking is retried on first play
      setTimeout(() => player.current?.seek(s.start, false), 200)
    }
  }, [release, states])

  useEffect(() => { setPin(null) }, [state?.key])

  // the peaks of the soundtrack playing, once per release and track
  useEffect(() => {
    let live = true
    setWaveform(null)
    if (release) loadWaveform(release, track).then((w) => { if (live) setWaveform(w) })
    return () => { live = false }
  }, [release, track])

  useEffect(() => {
    const h = hashFor(release, state)
    if (h && location.hash !== h) history.replaceState(null, '', h)
  }, [release, state])

  // --- seen / unread ------------------------------------------------------------------ //

  useEffect(() => {
    if (!release || !state) return
    const k = seenKey(release.id, state.key)
    const latest = comments
      .filter((c) => c.releaseId === release.id && c.stateKey === state.key)
      .reduce((m, c) => (c.createdAt > m ? c.createdAt : m), '')
    const stamp = latest || new Date().toISOString()
    if (seen[k] !== stamp) {
      const next = { ...seen, [k]: stamp }
      setSeen(next)
      prefs.setSeen(next)
    }
  }, [release, state, comments, seen])

  const counts = useMemo(() => {
    const m = new Map<string, Counts>()
    if (!release) return m
    for (const c of comments) {
      if (c.releaseId !== release.id) continue
      const e = m.get(c.stateKey) ?? { total: 0, open: 0, unread: 0 }
      e.total++
      if (!c.done) e.open++
      const last = seen[seenKey(release.id, c.stateKey)]
      if (c.author !== name && (!last || c.createdAt > last)) e.unread++
      m.set(c.stateKey, e)
    }
    return m
  }, [comments, release, seen, name])

  // Every note, where it is in the timeline: at its frame, or the middle of its state.
  const noteMarks: NoteMark[] = useMemo(() => {
    if (!release) return []
    const byKey = new Map(states.map((s) => [s.key, s]))
    return comments
      .filter((c) => c.releaseId === release.id && !c.parentId && c.kind === 'comment' && byKey.has(c.stateKey))
      .map((c) => {
        const s = byKey.get(c.stateKey)!
        return {
          id: c.id, key: c.stateKey, at: c.at ?? (s.start + s.end) / 2, assignees: c.assignees ?? [], author: c.author,
          body: c.body, done: c.done, replies: comments.filter((r) => r.parentId === c.id).length, topic: c.topic,
        }
      })
  }, [comments, release, states])

  const updates: Update[] = useMemo(() => {
    if (!release) return []
    const out: Update[] = []
    for (const s of states) {
      const c = counts.get(s.key)
      if (!c?.unread) continue
      const last = seen[seenKey(release.id, s.key)]
      const fresh = comments
        .filter((x) => x.releaseId === release.id && x.stateKey === s.key && x.author !== name && (!last || x.createdAt > last))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      out.push({ state: s, count: fresh.length, latestAuthor: fresh[0]?.author ?? '', latestAt: fresh[0]?.createdAt ?? '' })
    }
    return out.sort((a, b) => b.latestAt.localeCompare(a.latestAt))
  }, [release, states, counts, comments, seen, name])

  const markAllRead = () => {
    if (!release) return
    const now = new Date().toISOString()
    const next = { ...seen }
    for (const s of states) next[seenKey(release.id, s.key)] = now
    setSeen(next)
    prefs.setSeen(next)
  }

  // --- the thread ------------------------------------------------------------------- //

  const generalComments = useMemo(
    () => (release ? comments.filter((c) => c.releaseId === release.id && c.stateKey === GENERAL) : []),
    [comments, release],
  )
  const generalEarlier = useMemo(
    () =>
      release
        ? releases
            .filter((r) => r.id !== release.id)
            .map((r) => ({ release: r, comments: comments.filter((c) => c.releaseId === r.id && c.stateKey === GENERAL && !c.done) }))
            .filter((e) => e.comments.length)
        : [],
    [releases, release, comments],
  )

  const threadComments = useMemo(
    () => (release && state ? comments.filter((c) => c.releaseId === release.id && c.stateKey === state.key) : []),
    [comments, release, state],
  )

  const earlier = useMemo(() => {
    if (!release || !state) return []
    return releases
      .filter((r) => r.id !== release.id)
      .map((r) => ({ release: r, comments: comments.filter((c) => c.releaseId === r.id && c.slideId === state.slide && !c.done) }))
      .filter((e) => e.comments.length)
  }, [releases, release, state, comments])

  // The name reaches the action as an argument rather than off the closure: an action
  // queued behind the name dialog was made while the name was still empty.
  const withName = (then: (author: string) => void) => {
    if (name) then(name)
    else setAskName({ then })
  }

  const post = (body: string, kind: CommentKind = 'comment', topic: CommentTopic = 'visual', assignees: string[] = [], general = false, parentId: string | null = null) =>
    withName(async (author) => {
      if (!release || (!state && !general)) return
      const slideId = general ? GENERAL : state!.slide
      const stateKey = general ? GENERAL : state!.key
      const pinned = !general && kind === 'comment' && !parentId
        ? pin ? { at: pin.at, x: pin.x, y: pin.y } : { at: time, x: null, y: null }
        : {}
      try {
        const c = await store.addComment({ kind, topic, releaseId: release.id, slideId, stateKey, author, body, assignees, parentId, ...pinned })
        setComments((all) => [...all, c])
        if (pin && !general && kind === 'comment' && !parentId) setPin(null)
      } catch (e) {
        setError(`Could not post: ${(e as Error).message}`)
      }
    })

  const setDone = (id: string, done: boolean) =>
    withName(async (author) => {
      try {
        await store.setDone(id, done, author)
        setComments((all) =>
          all.map((c) => (c.id === id ? { ...c, done, doneBy: done ? author : null, doneAt: done ? new Date().toISOString() : null } : c)),
        )
      } catch (e) {
        setError(`Could not update: ${(e as Error).message}`)
      }
    })

  const reply = (parentId: string, body: string) => {
    const parent = comments.find((c) => c.id === parentId)
    // A reply belongs where its parent stands: under a voice-over line it takes that track's own
    // kind, so it is shown with the line rather than loose in the thread.
    const kind: CommentKind = parent?.kind.startsWith('voiceover')
      ? ((parent.kind.endsWith('_note') ? parent.kind : `${parent.kind}_note`) as CommentKind)
      : 'comment'
    post(body, kind, parent?.topic ?? 'visual', [], parent?.stateKey === GENERAL, parentId)
  }

  const editComment = async (id: string, body: string) => {
    try {
      await store.editComment(id, body)
      setComments((all) => all.map((c) => (c.id === id ? { ...c, body, editedAt: new Date().toISOString() } : c)))
    } catch (e) {
      setError(`Could not edit: ${(e as Error).message}`)
    }
  }

  const deleteComment = async (id: string) => {
    if (!confirm('Delete this comment?')) return
    try {
      await store.deleteComment(id)
      setComments((all) => all.filter((c) => c.id !== id))
    } catch (e) {
      setError(`Could not delete: ${(e as Error).message}`)
    }
  }

  // --- what others just did ---------------------------------------------------------- //

  /** One comment as an activity: who did what, where, and how to get there. */
  const describe = (c: Comment): Toast => {
    const r = releases.find((x) => x.id === c.releaseId)
    const s = r?.manifest.states.find((x) => x.key === c.stateKey)
    const parent = c.parentId ? comments.find((x) => x.id === c.parentId) : null
    const general = c.stateKey === GENERAL
    const you = c.author === name
    const who = you ? 'You' : c.author
    const mine = !you && !!name && (parent?.author === name || !!c.assignees?.includes(name))
    const title = parent
      ? parent.author === name && !you ? `${who} replied to your note` : `${who} replied`
      : c.kind === 'voiceover' ? `${who} changed the voice-over`
      : c.kind === 'voiceover_extended' ? `${who} changed the extended voice-over`
      : c.kind === 'voiceover_note' ? `${who} wrote about the voice-over`
      : c.kind === 'voiceover_extended_note' ? `${who} wrote about the extended voice-over`
      : !you && c.assignees?.includes(name) ? `${who} assigned you a note`
      : `${who} added ${general ? 'a general comment' : `a ${c.topic} note`}`
    const where = general ? 'General comments' : `${c.stateKey}${r && r.id !== release?.id ? ` · ${r.name}` : ''}`
    const target = parent ?? c
    return {
      id: c.id,
      title,
      body: c.body.length > 140 ? c.body.slice(0, 140) + '…' : c.body,
      where,
      mine,
      at: c.createdAt,
      onOpen: () => {
        if (general || target.stateKey === GENERAL) { setGeneral(true); return }
        if (r && r.id !== release?.id) setReleaseId(r.id)
        goToNote(target.id, target.at ?? null, s?.start ?? 0)
      },
    }
  }

  const activity = useMemo(
    () => [...comments].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 5).map(describe),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [comments, releases, release, name],
  )

  useEffect(() => {
    if (!comments.length && known.current == null) return
    if (known.current == null) {
      known.current = new Set(comments.map((c) => c.id))
      return
    }
    const fresh = comments.filter((c) => !known.current!.has(c.id))
    fresh.forEach((c) => known.current!.add(c.id))
    const theirs = fresh.filter((c) => c.author !== name)
    if (!theirs.length) return

    const next: Toast[] = theirs.map(describe)
    setToasts((t) => [...next, ...t].slice(0, 4))
    setRing((n) => n + 1)
    next.forEach((t) => setTimeout(() => setToasts((all) => all.filter((x) => x.id !== t.id)), 8000))
  }, [comments])

  // --- navigation ------------------------------------------------------------------- //

  const goTo = useCallback(
    (index: number, play = true) => {
      const s = states[index]
      if (!s) return
      setTime(s.start)
      player.current?.seek(s.start, play)
    },
    [states],
  )

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      // the comment box holds the focus so the cursor blinks there; while it is empty the
      // film's keys still work from inside it
      const emptyComposer = t instanceof HTMLTextAreaElement && t.hasAttribute('data-composer') && t.value === ''
      if ((t.tagName === 'TEXTAREA' || t.tagName === 'INPUT' || t.tagName === 'SELECT') && !emptyComposer) return
      if (e.key === 'ArrowRight') goTo(Math.min(states.length - 1, current + 1), playing)
      else if (e.key === 'ArrowLeft') goTo(Math.max(0, current - 1), playing)
      else if (e.key === ' ') {
        e.preventDefault()
        player.current?.toggle()
      } else if (e.key.toLowerCase() === 'l') toggleLoop()
      else if (e.key === '0') goTo(0, playing)
      else if (e.key === ',') player.current?.step(-1)
      else if (e.key === '.') player.current?.step(1)
      else if (e.key.toLowerCase() === 'c') setCommenting((v) => !v)
      else if (e.key === 'Escape') { setPin(null); setCommenting(false) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const toggleLoop = () => {
    setLoop((v) => {
      prefs.setLoop(!v)
      return !v
    })
  }

  const onTime = useCallback((t: number) => setTime(t), [])
  const onPlaying = useCallback((p: boolean) => setPlaying(p), [])

  /**
   * The soundtracks to choose between: the film's own mix first, then any the release carries
   * beside it — the same picture with the voice-over left out, say. Undefined where there is
   * only one, and the switch is then not drawn.
   */
  const tracks = useMemo(() => {
    const extra = release?.manifest.audio ?? []
    if (!extra.length) return undefined
    const base = release?.thumbBase ?? ''
    return [
      { key: 'film', name: 'with voice' },
      ...extra.map((a) => ({ key: a.key, name: a.name, audio: /^(https?:|blob:|data:)/.test(a.file) ? a.file : `${base}${a.file}` })),
    ]
  }, [release])

  useEffect(() => { prefs.setTrack(track) }, [track])
  useEffect(() => { prefs.setVoiceTrack(voiceTrack) }, [voiceTrack])

  const thumbUrl = useCallback(
    (s: StateInfo) => (/^(https?:|blob:|data:)/.test(s.thumb) ? s.thumb : `${release?.thumbBase ?? ''}${s.thumb}`),
    [release],
  )

  const exportCsv = () => {
    download(`wn-review-comments-${new Date().toISOString().slice(0, 10)}.csv`, commentsCsv(comments, releases))
  }

  const addRelease = async (input: NewReleaseInput, progress: (l: string, f: number) => void) => {
    const r = await store.addRelease(input, progress)
    await refresh()
    setReleaseId(r.id)
    openedHash.current = true
    setTime(0)
  }

  // Pinned comments standing on the picture: from their frame, for PIN_HOLD seconds.
  const dots: Dot[] = useMemo(() => {
    if (!release) return []
    return comments
      .filter((c) => c.releaseId === release.id && c.at != null && c.x != null && c.y != null && c.stateKey === state?.key &&
        (playing ? time >= c.at - 1 / fps / 2 && time < c.at + PIN_HOLD : true))
      .filter((c) => !c.parentId)
      .map((c, i) => ({
        id: c.id, x: c.x!, y: c.y!, author: c.author, body: c.body, done: c.done, n: i + 1,
        replies: comments.filter((r) => r.parentId === c.id).map((r) => ({ id: r.id, author: r.author, body: r.body })),
      }))
  }, [comments, release, time, fps, state, playing])

  const seekMarks = useMemo(
    () =>
      release && state
        ? comments
            .filter((c) => c.releaseId === release.id && c.at != null && c.at >= state.start && c.at < state.end)
            .map((c) => ({ at: c.at!, author: c.author, body: c.body, done: c.done }))
        : [],
    [comments, release, state],
  )

  /** To a note: its frame, paused, with the note open on the picture. */
  const goToNote = (id: string, at: number | null, fallback: number) => {
    scrub(at ?? fallback)
    setFocus((f) => ({ id, n: (f?.n ?? 0) + 1 }))
  }

  const scrub = (t: number) => {
    setTime(t)
    player.current?.pause()
    player.current?.seek(t, false)
  }

  // --- the page --------------------------------------------------------------------- //

  return (
    <div className="app">
      <Header
        releases={releases}
        release={release}
        onRelease={(id) => { setReleaseId(id); setTime(0); player.current?.pause() }}
        updates={updates}
        onJump={(i) => goTo(i, false)}
        onMarkAllRead={markAllRead}
        name={name}
        onName={() => setAskName({})}
        onNewRelease={() => setNewRelease(true)}
        generalOpen={generalComments.filter((c) => !c.done).length}
        onGeneral={() => setGeneral(true)}
        tasksOpen={release ? comments.filter((c) => c.releaseId === release.id && !c.parentId && c.kind === 'comment' && !c.done && c.assignees && c.assignees.length > 0).length : 0}
        onTasks={() => setTasks(true)}
        ring={ring}
        activity={activity}
        onExport={exportCsv}
        mode={store.kind}
      />

      {error && <div className="banner">{error}</div>}

      <main className="main">
        <section className="stage">
          {release && (
            <Timeline
              notes={noteMarks}
              states={states}
              thumbUrl={thumbUrl}
              duration={release.manifest.duration}
              time={time}
              current={current}
              onSeek={(t) => { setTime(t); player.current?.seek(t, playing) }}
              onJump={(t) => { setTime(t); player.current?.seek(t, true) }}
              onJumpNote={(id, t) => goToNote(id, t, t)}
            />
          )}
          {release ? (
            <Player
              key={release.id}
              ref={player}
              src={release.videoUrl}
              tracks={tracks}
              track={track}
              onTrack={setTrack}
              loopRange={loop && state ? { start: state.start, end: state.end } : null}
              onTime={onTime}
              onPlaying={onPlaying}
              paused={!playing}
              fps={fps}
              commenting={commenting}
              onCommenting={setCommenting}
              pending={pin && pin.x != null && pin.y != null ? { x: pin.x, y: pin.y } : null}
              pendingBox={
                pin && state ? (
                  <NoteComposer
                    frame={Math.round((pin.at - state.start) * fps)}
                    onAdd={(body, topic, assignees) => post(body, 'comment', topic, assignees)}
                    onCancel={() => setPin(null)}
                  />
                ) : null
              }
              onPlace={(x, y) => { setPin({ at: time, x, y }); setCommenting(false) }}
              onMove={(x, y) => setPin((p) => (p ? { ...p, x, y } : p))}
              dots={dots}
              onDotDone={setDone}
              onDotEdit={editComment}
              onDotDelete={deleteComment}
              onDotReply={reply}
              onReplyEdit={editComment}
              onReplyDelete={deleteComment}
              focus={focus}
            />
          ) : (
            <div className="player empty-stage">
              {loading ? 'Loading…' : 'No release yet. Build one with tools/release_build.py, or add one with “+ release”.'}
            </div>
          )}

          {state && (
            <div className="state-line">
              <button className={`add-note${commenting ? ' on' : ''}`} onClick={() => { player.current?.pause(); setCommenting((v) => !v) }} title="Then click the spot on the picture (C)">
                <svg width="16" height="16" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"><path d="M3 4.5A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v6a1.5 1.5 0 0 1-1.5 1.5H8l-4 3.5V12H4.5A1.5 1.5 0 0 1 3 10.5z" /></svg>
                {commenting ? 'Click the picture…' : 'Add note'}
              </button>
              <CopyKey value={state.key} big />
              <span className="state-line-title">{state.title}</span>
              {state.chapter && <span className="state-line-chapter">{state.chapter}</span>}
            </div>
          )}
          {state && (
            <StateSeeker
              state={state}
              fps={fps}
              time={time}
              marks={seekMarks}
              waveform={waveform}
              onScrub={scrub}
              onStep={(n) => player.current?.step(n)}
            />
          )}
          <div className="controls">
            <button onClick={() => goTo(Math.max(0, current - 1), playing)} disabled={current <= 0} title="Previous state (←)">◀</button>
            <button className="play" onClick={() => player.current?.toggle()} disabled={!release} title="Play / pause (space)">
              {playing ? '❚❚' : '▶'}
            </button>
            <button onClick={() => goTo(Math.min(states.length - 1, current + 1), playing)} disabled={current >= states.length - 1} title="Next state (→)">▶</button>
            <button className={`toggle${loop ? ' on' : ''}`} onClick={toggleLoop} title="Loop this state (L)">loop</button>
            <span className="clock">
              {state ? `${formatTime(time - state.start)} / ${formatTime(state.end - state.start)}` : ''}
              <em>{release ? ` · ${formatTime(time)} of ${formatTime(release.manifest.duration)}` : ''}</em>
            </span>
            <span className="spacer" />
            {state && <span className="where">{current + 1} / {states.length}</span>}
          </div>


        </section>

        {release && (
          <aside className="side">
          <Thread
            release={release}
            state={state}
            comments={threadComments}
            earlier={earlier}
            name={name}
            thumbUrl={thumbUrl}
            onPost={post}
            onDone={setDone}
            onEdit={editComment}
            onDelete={deleteComment}
            fps={fps}
            onGoTo={(at, id) => goToNote(id, at, at)}
            onReply={reply}
            onWrite={(body, topic, assignees) => post(body, 'comment', topic, assignees)}
            onName={() => setAskName({})}
          />
          </aside>
        )}
        {release && state && (
          <VoiceOver
            key={state.key}
            state={state}
            tracks={[
              ...([
                { key: 'voiceover', label: 'subtitle', text: state.voiceover ?? '' },
                { key: 'voiceover_extended', label: 'extended', text: state.voiceoverExtended ?? '' },
              ] as const).map((t) => {
                const here = comments.filter((c) => c.releaseId === release.id && c.stateKey === state.key)
                const notes = here.filter((c) => c.kind === `${t.key}_note` && !c.parentId)
                return {
                  ...t,
                  updates: here.filter((c) => c.kind === t.key),
                  notes,
                  replies: here.filter((c) => c.kind === `${t.key}_note` && c.parentId),
                }
              }),
            ]}
            track={voiceTrack}
            onTrack={setVoiceTrack}
            name={name}
            onSave={(track, text) => post(text, track as CommentKind)}
            onNote={(track, body) => post(body, `${track}_note` as CommentKind, 'audio')}
            onReply={reply}
            onDone={setDone}
            onEdit={editComment}
            onDelete={deleteComment}
          />
        )}
      </main>

      {/* The strip of every state along the foot of the page — hidden for now (22 September), kept to bring back:
      <Minimap states={states} current={current} time={time} counts={counts} thumbUrl={thumbUrl} onSelect={(i) => goTo(i, true)} />
      */}

      {askName && (
        <NameDialog
          initial={name}
          onSave={(n) => {
            setName(n)
            prefs.setName(n)
            const then = askName.then
            setAskName(null)
            then?.(n)
          }}
          onCancel={() => setAskName(null)}
        />
      )}
      <Toasts toasts={toasts} onClose={(id) => setToasts((t) => t.filter((x) => x.id !== id))} />
      {tasks && release && (
        <div className="modal-backdrop" onClick={() => setTasks(false)}>
          <div className="modal general tasks" onClick={(e) => e.stopPropagation()}>
            <div className="general-head">
              <h2>Tasks</h2>
              <span className="muted">{release.name}</span>
              <button className="link" onClick={() => setTasks(false)}>close</button>
            </div>
            <TasksTab
              release={release}
              comments={comments.filter((c) => c.releaseId === release.id)}
              onGo={(s, at, id) => { setTasks(false); goToNote(id, at != null && at >= s.start && at < s.end ? at : null, s.start) }}
              onDone={setDone}
            />
          </div>
        </div>
      )}
      {general && release && (
        <General
          release={release}
          comments={generalComments}
          earlier={generalEarlier}
          name={name}
          onPost={(body) => post(body, 'comment', 'visual', [], true)}
          onDone={setDone}
          onEdit={editComment}
          onDelete={deleteComment}
          onReply={reply}
          onClose={() => setGeneral(false)}
        />
      )}
      {newRelease && <NewReleaseDialog local={store.kind === 'local'} onSubmit={addRelease} onClose={() => setNewRelease(false)} />}
    </div>
  )
}
