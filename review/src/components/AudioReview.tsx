import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Player, { type PlayerHandle } from './Player'
import AudioTimeline, { type SlotNotes } from './AudioTimeline'
import ClipPanel, { AudioOverview } from './ClipPanel'
import { GAIN_MAX, GAIN_MIN, buildLanes, clock, dbToGain, fileUrl, filmDb, gainsOf, loadAudio, statusesOf, type Placed, type Slot } from '../lib/audio'
import { Remix, type Listen } from '../lib/remix'
import { gainsJson } from '../lib/gains'
import { download } from '../lib/csv'
import { AUDIO_TEAM, colourOf } from '../lib/team'
import type { AudioDoc, ClipStatus, Comment, Release, StateInfo } from '../lib/types'

export type NewNote = Omit<Comment, 'id' | 'createdAt' | 'done' | 'doneBy' | 'doneAt' | 'author'>

type Filter = 'all' | 'undone' | 'needs-work' | 'done' | 'missing' | 'notes' | 'gain'
const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'undone', label: 'Undone' },
  { key: 'needs-work', label: 'Needs work' },
  { key: 'done', label: 'Done' },
  { key: 'missing', label: 'Missing' },
  { key: 'notes', label: 'Open notes' },
  { key: 'gain', label: 'Gain set' },
]
/** A frame of the film at the most: past this the clips are wider than anything in them. */
const MAX_PPS = 400

interface Props {
  release: Release
  releases: Release[]
  comments: Comment[]
  name: string
  onName(): void
  /** The selected sound, by slot: kept by the page so the address and a release switch keep it. */
  slot: string | null
  onSlot(key: string | null): void
  /** A sound to go to and play, from a notification or a task; `n` changes on every ask. */
  focus: { key: string; n: number } | null
  onAdd(c: NewNote): void
  onDone(id: string, done: boolean): void
  onEdit(id: string, body: string): void
  onDelete(id: string): void
  tracks?: { key: string; name: string; audio?: string }[]
  track: string
  onTrack(key: string): void
  thumbUrl(s: StateInfo): string
}

/**
 * The audio timeline review: every sound the film played on a timeline of its own, a small player
 * over it, and a status and a thread on every sound — and on every place a sound is missing.
 */
export default function AudioReview(p: Props) {
  const { release } = p
  const states = release.manifest.states
  const duration = release.manifest.duration
  const fps = release.manifest.fps
  const player = useRef<PlayerHandle>(null)

  const [doc, setDoc] = useState<AudioDoc | null>(null)
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    let live = true
    setLoading(true)
    loadAudio(release).then((d) => { if (live) { setDoc(d); setLoading(false) } })
    return () => { live = false }
  }, [release])

  const lanes = useMemo(() => (doc ? buildLanes(doc, states) : []), [doc, states])
  const slots = useMemo(() => {
    const m = new Map<string, Slot>()
    for (const l of lanes) for (const s of l.slots) m.set(s.key, s)
    return m
  }, [lanes])

  // --- what is said about the sounds ------------------------------------------------- //

  const statuses = useMemo(() => statusesOf(p.comments), [p.comments])
  const statusOf = useCallback((k: string): ClipStatus => statuses.get(k)?.status ?? 'undone', [statuses])

  const notesBySlot = useMemo(() => {
    const m = new Map<string, Comment[]>()
    for (const c of p.comments) {
      if (c.kind !== 'comment' || !c.clip || c.parentId) continue
      const list = m.get(c.clip) ?? []
      list.push(c)
      m.set(c.clip, list)
    }
    for (const list of m.values()) list.sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    return m
  }, [p.comments])

  const summary = useMemo(() => {
    const m = new Map<string, SlotNotes>()
    for (const [k, list] of notesBySlot) {
      const open = list.filter((c) => !c.done)
      m.set(k, { open: open.length, total: list.length, people: [...new Set(open.flatMap((c) => c.assignees ?? []))] })
    }
    return m
  }, [notesBySlot])
  const notesOf = useCallback((k: string) => summary.get(k), [summary])

  // --- gain: the level each sound-design cue should play at, in dB against its file --------- //

  const gains = useMemo(() => gainsOf(p.comments), [p.comments])
  /** What each slot was filmed at: the reference a reviewed gain is weighed against. */
  const filmed = useMemo(() => {
    const m = new Map<string, number>()
    if (doc) for (const l of lanes) for (const s of l.slots) if (s.clips.length) m.set(s.key, filmDb(doc, s.clips[0]))
    return m
  }, [doc, lanes])
  /** The gain being dragged, heard at once and written when the fader is let go. */
  const [draft, setDraft] = useState<{ slot: string; db: number } | null>(null)
  const targetDb = useCallback(
    (key: string) => (draft?.slot === key ? draft.db : gains.get(key)?.db ?? filmed.get(key) ?? 0),
    [draft, gains, filmed],
  )
  const gainOf = useCallback((key: string) => ({ db: targetDb(key), film: filmed.get(key) ?? 0 }), [targetDb, filmed])
  // a draft goes once the row it was written as has come back
  useEffect(() => {
    if (draft && Math.abs((gains.get(draft.slot)?.db ?? NaN) - draft.db) < 0.05) setDraft(null)
  }, [gains, draft])

  // --- the filter ---------------------------------------------------------------------- //

  const [filter, setFilter] = useState<Filter>('all')
  const [who, setWho] = useState('')
  const test = useCallback((slot: Slot, f: Filter, person: string) => {
    const st = statusOf(slot.key)
    const gap = !!slot.missing && st !== 'done'
    const ok =
      f === 'all' ? true
      : f === 'missing' ? gap
      : f === 'notes' ? (summary.get(slot.key)?.open ?? 0) > 0
      : f === 'gain' ? slot.layer === 'design' && Math.abs(targetDb(slot.key)) >= 0.05
      : f === 'undone' ? st === 'undone' && !gap
      : st === f
    return ok && (!person || !!summary.get(slot.key)?.people.includes(person))
  }, [statusOf, summary, targetDb])
  const matches = useCallback((slot: Slot) => test(slot, filter, who), [test, filter, who])
  const counts = useMemo(() => {
    const all = [...slots.values()]
    const out = {} as Record<Filter, number>
    for (const f of FILTERS) out[f.key] = all.filter((s) => test(s, f.key, who)).length
    const people: Record<string, number> = {}
    for (const person of AUDIO_TEAM) people[person] = all.filter((s) => summary.get(s.key)?.people.includes(person)).length
    return { ...out, people }
  }, [slots, test, who, summary])

  // --- playing a sound's part of the film ---------------------------------------------- //

  const [time, setTime] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [clipId, setClipId] = useState<string | null>(null)
  const [loop, setLoop] = useState(false)
  const [listen, setListen] = useState<Listen>('film')
  const [follow, setFollow] = useState(true)
  const [reveal, setReveal] = useState<{ t: number; end: number; n: number } | null>(null)
  const stopAt = useRef<number | null>(null)

  const selected = p.slot ? slots.get(p.slot) ?? null : null
  useEffect(() => { setDraft((d) => (d && d.slot !== p.slot ? null : d)) }, [p.slot])
  const active: Placed | null = selected ? selected.clips.find((c) => c.id === clipId) ?? selected.clips[0] ?? null : null
  const range = active ? { start: active.start, end: active.end } : selected?.missing ? { start: selected.missing.start, end: selected.missing.end } : null

  const onTime = useCallback((t: number) => {
    setTime(t)
    const s = stopAt.current
    if (s != null && t >= s - 0.02) {
      stopAt.current = null
      player.current?.pause()
    }
  }, [])
  const onPlaying = useCallback((v: boolean) => setPlaying(v), [])

  const loopRef = useRef(loop)
  loopRef.current = loop
  const remix = useRef<Remix | null>(null)
  if (!remix.current) remix.current = new Remix((c) => fileUrl(release, c.info), fps)
  useEffect(() => () => remix.current?.stop(), [])
  const wake = () => remix.current?.wake()

  /** Selects a sound and plays its part of the film, stopping where it stops. */
  const play = useCallback((slot: Slot, clip: Placed | null, start = true) => {
    if (start) remix.current?.wake()
    const c = clip ?? slot.clips[0] ?? null
    const from = c ? c.start : slot.missing?.start ?? slot.start
    const to = c ? c.end : slot.missing?.end ?? slot.end
    p.onSlot(slot.key)
    setClipId(c?.id ?? null)
    setReveal((r) => ({ t: from, end: to, n: (r?.n ?? 0) + 1 }))
    stopAt.current = start && !loopRef.current ? to : null
    setTime(from)
    player.current?.seek(from, start)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p.onSlot])

  const onSelect = useCallback((slot: Slot, clip: Placed | null) => play(slot, clip), [play])
  const playingRef = useRef(playing)
  playingRef.current = playing
  const onSeek = useCallback((t: number) => {
    stopAt.current = null
    setTime(t)
    player.current?.seek(t, playingRef.current)
  }, [])

  // a sound named in the address: there, paused, once the timeline is in
  const opened = useRef(false)
  useEffect(() => {
    if (opened.current || !doc) return
    opened.current = true
    const s = p.slot ? slots.get(p.slot) : null
    if (s) setTimeout(() => play(s, null, false), 150)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [doc])

  // a sound asked for from a notification or a task
  useEffect(() => {
    if (!p.focus || !doc) return
    const s = slots.get(p.focus.key)
    if (s) play(s, null, false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [p.focus?.n, doc])

  // Remix and solo: the soundtrack rebuilt from its files at the reviewed gains, or the selected
  // sound alone at its own — the film silent under either.
  const allClips = useMemo(() => lanes.flatMap((l) => l.slots.flatMap((s) => s.clips)), [lanes])
  const levelOf = useCallback(
    (c: Placed) => c.gain * (c.layer === 'design' ? dbToGain(targetDb(c.slot) - (filmed.get(c.slot) ?? 0)) : 1),
    [targetDb, filmed],
  )
  useEffect(() => {
    const r = remix.current!
    if (listen === 'film') { r.stop(); return }
    r.update(time, playing, listen === 'solo' ? (active ? [active] : []) : allClips, levelOf)
  }, [time, playing, listen, active, allClips, levelOf])
  useEffect(() => {
    if (import.meta.env.DEV) (window as unknown as { remix?: Remix }).remix = remix.current ?? undefined
  }, [])
  const hear = (l: Listen) => { wake(); setListen(l) }

  // --- zoom ------------------------------------------------------------------------- //

  const [viewW, setViewW] = useState(1200)
  const minPps = Math.max(0.05, (viewW - 4) / Math.max(duration, 1))
  const [zoom, setZoom] = useState<number | null>(null)
  const pps = Math.min(MAX_PPS, Math.max(minPps, zoom ?? minPps))
  const zoomBy = (f: number) => setZoom(Math.min(MAX_PPS, Math.max(minPps, pps * f)))
  const slider = Math.log(pps / minPps) / Math.log(MAX_PPS / minPps)

  // --- moving through the sounds ----------------------------------------------------- //

  const laneOf = (s: Slot | null) => lanes.findIndex((l) => l.layer === s?.layer)
  const step = (dir: 1 | -1) => {
    const li = selected ? laneOf(selected) : lanes.findIndex((l) => l.layer === 'design')
    const lane = lanes[li]
    if (!lane) return
    const list = lane.slots
    const i = selected ? list.findIndex((s) => s.key === selected.key) : -1
    const next = i >= 0 ? list[i + dir] : dir > 0 ? list.find((s) => s.start >= time - 1e-3) : [...list].reverse().find((s) => s.start < time)
    if (next) play(next, null)
  }
  const switchLane = (dir: 1 | -1) => {
    const li = (selected ? laneOf(selected) : 0) + dir
    const lane = lanes[li]
    if (!lane || !lane.slots.length) return
    const at = time
    const inside = lane.slots.find((s) => s.start <= at && at < s.end)
    const nearest = inside ?? [...lane.slots].sort((a, b) => Math.abs(a.start - at) - Math.abs(b.start - at))[0]
    play(nearest, null)
  }
  const nextMatch = () => {
    const all = [...slots.values()].filter(matches).sort((a, b) => a.start - b.start || laneOf(a) - laneOf(b))
    if (!all.length) return
    const from = selected ? all.findIndex((s) => s.key === selected.key) : -1
    const next = from >= 0 ? all[(from + 1) % all.length] : all.find((s) => s.start >= time) ?? all[0]
    play(next, null)
  }

  const setStatus = (s: ClipStatus) => {
    if (!selected || statusOf(selected.key) === s) return
    p.onAdd({
      kind: 'audio_status', topic: 'audio', releaseId: release.id, slideId: selected.state.slide, stateKey: selected.state.key,
      body: s, clip: selected.key, clipFile: selected.clips[0]?.info.name ?? null, at: null, x: null, y: null, assignees: [], parentId: null,
    })
  }

  const addNote = (body: string, assignees: string[]) => {
    if (!selected) return
    p.onAdd({
      kind: 'comment', topic: 'audio', releaseId: release.id, slideId: selected.state.slide, stateKey: selected.state.key,
      body, assignees, clip: selected.key, clipFile: active?.info.name ?? null,
      at: range?.start ?? null, x: null, y: null, parentId: null,
    })
  }

  const reply = (parentId: string, body: string) => {
    const parent = p.comments.find((c) => c.id === parentId)
    if (!parent) return
    p.onAdd({
      kind: 'comment', topic: 'audio', releaseId: parent.releaseId, slideId: parent.slideId, stateKey: parent.stateKey,
      body, assignees: [], clip: parent.clip ?? null, clipFile: parent.clipFile ?? null, at: null, x: null, y: null, parentId,
    })
  }

  const deselect = () => { p.onSlot(null); setClipId(null) }

  /** Writes a gain for the selected cue, where it differs from the one it has. */
  const commitGain = (db: number) => {
    if (!selected || selected.layer !== 'design' || !selected.clips.length) return
    const next = Math.round(Math.min(GAIN_MAX, Math.max(GAIN_MIN, db)) * 10) / 10
    const now = gains.get(selected.key)?.db ?? filmed.get(selected.key) ?? 0
    if (Math.abs(next - now) < 0.05) { setDraft(null); return }
    // a gain is set to be heard: in the film's own track it cannot be
    if (listen === 'film') hear('remix')
    setDraft({ slot: selected.key, db: next })
    p.onAdd({
      kind: 'audio_gain', topic: 'audio', releaseId: release.id, slideId: selected.state.slide, stateKey: selected.state.key,
      body: next.toFixed(1), clip: selected.key, clipFile: selected.clips[0].info.name, at: null, x: null, y: null, assignees: [], parentId: null,
    })
  }
  /** Hears a gain while it is being set, and switches to the remix so it can be heard in place. */
  const draftGain = (db: number) => {
    if (!selected) return
    if (listen === 'film') hear('remix')
    setDraft({ slot: selected.key, db: Math.round(db * 10) / 10 })
  }
  const exportGains = () => download('show-gains.json', gainsJson(p.comments), 'application/json')
  const gainSummary = useMemo(() => {
    let set = 0, waiting = 0
    for (const [k, g] of gains) {
      if (!k.startsWith('design:')) continue
      if (Math.abs(g.db) >= 0.05) set++
      if (filmed.has(k) && Math.abs(g.db - (filmed.get(k) ?? 0)) >= 0.05) waiting++
    }
    return { set, waiting }
  }, [gains, filmed])

  // --- keys --------------------------------------------------------------------------- //

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT' || t.tagName === 'SELECT' || e.metaKey || e.ctrlKey || e.altKey) return
      wake()
      const k = e.key
      if (k === ' ') { e.preventDefault(); player.current?.toggle() }
      else if (k === 'ArrowRight') { e.preventDefault(); step(1) }
      else if (k === 'ArrowLeft') { e.preventDefault(); step(-1) }
      else if (k === 'ArrowDown') { e.preventDefault(); switchLane(1) }
      else if (k === 'ArrowUp') { e.preventDefault(); switchLane(-1) }
      else if (k === 'Enter' && selected) play(selected, active)
      else if (k === 'Escape') deselect()
      else if (k.toLowerCase() === 's') setListen((l) => (l === 'solo' ? 'film' : 'solo'))
      else if (k.toLowerCase() === 'm') setListen((l) => (l === 'remix' ? 'film' : 'remix'))
      else if ((k === '[' || k === ']') && selected?.layer === 'design') {
        commitGain(targetDb(selected.key) + (k === ']' ? 1 : -1) * (e.shiftKey ? 0.5 : 1))
      }
      else if (k.toLowerCase() === 'l') setLoop((v) => !v)
      else if (k.toLowerCase() === 'f') setFollow((v) => !v)
      else if (k === '1') setStatus('undone')
      else if (k === '2') setStatus('needs-work')
      else if (k === '3') setStatus('done')
      else if (k === '=' || k === '+') zoomBy(1.6)
      else if (k === '-') zoomBy(1 / 1.6)
      else if (k === '\\') setZoom(null)
      else if (k === ',') player.current?.step(-1)
      else if (k === '.') player.current?.step(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  useEffect(() => {
    const was = document.title
    document.title = 'Audio timeline review · WN review'
    return () => { document.title = was }
  }, [])

  const attention = useMemo(() => {
    const out: { slot: Slot; why: string }[] = []
    for (const s of slots.values()) {
      const st = statusOf(s.key)
      const open = summary.get(s.key)?.open ?? 0
      const why = [
        st === 'needs-work' ? 'needs work' : '',
        s.missing && st !== 'done' ? 'missing' : '',
        s.clips.some((c) => c.info.silent) && st !== 'done' ? 'silent file' : '',
        s.layer === 'design' && s.clips.some((c) => (c.info.peakDb ?? -99) + targetDb(s.key) > 0) ? 'clips at its gain' : '',
        open ? `${open} open ${open === 1 ? 'note' : 'notes'}` : '',
      ].filter(Boolean)
      if (why.length) out.push({ slot: s, why: why.join(' · ') })
    }
    return out.sort((a, b) => a.slot.start - b.slot.start)
  }, [slots, statusOf, summary, targetDb])

  // --- the page --------------------------------------------------------------------- //

  return (
    <div className="audio-page">
      <div className="audio-main">
        <div className="audio-stage">
          <Player
            key={release.id}
            ref={player}
            src={release.videoUrl}
            tracks={p.tracks}
            track={p.track}
            onTrack={p.onTrack}
            loopRange={loop && range ? range : null}
            onTime={onTime}
            onPlaying={onPlaying}
            paused={!playing}
            fps={fps}
            commenting={false}
            onCommenting={() => {}}
            dots={[]}
            forceMute={listen !== 'film'}
            noComments
          />
          <div className="audio-transport">
            <button onClick={() => step(-1)} title="Previous sound on the track (←)">⏮</button>
            <button className="play" onClick={() => { wake(); player.current?.toggle() }} title="Play / pause (space)">{playing ? '❚❚' : '▶'}</button>
            <button onClick={() => step(1)} title="Next sound on the track (→)">⏭</button>
            <button onClick={() => selected && play(selected, active)} disabled={!selected} title="Play the selected sound's part of the film (Enter)">▶ play sound</button>
            <button className={`toggle${loop ? ' on' : ''}`} onClick={() => setLoop((v) => !v)} title="Loop the selected sound (L)">loop</button>
            <div className="listen" role="group" aria-label="What you hear">
              <button className={listen === 'film' ? 'on' : ''} onClick={() => hear('film')} title="The film's own soundtrack, as it was filmed">film</button>
              <button className={listen === 'remix' ? 'on' : ''} onClick={() => hear('remix')} title="The soundtrack rebuilt from its files at the gains set here — hear a gain in place before the next release (M)">remix</button>
              <button className={listen === 'solo' ? 'on' : ''} onClick={() => hear('solo')} disabled={!active?.info.audio} title="The selected sound on its own, at its gain (S)">solo</button>
            </div>
            <span className="clock">
              {clock(time)}
              {range && <em> · {time >= range.start && time <= range.end ? `${clock(time - range.start)} of ${clock(range.end - range.start)}` : `sound at ${clock(range.start)}`}</em>}
            </span>
            <span className="spacer" />
            <label className="follow" title="Keep the playhead in view while playing (F)">
              <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} /> follow
            </label>
          </div>
        </div>

        <div className="audio-bar">
          <span className="assign-label">Show</span>
          {FILTERS.map((f) => (
            <button key={f.key} className={`filter-chip f-${f.key}${filter === f.key ? ' on' : ''}`} onClick={() => setFilter(f.key)}>
              {f.key !== 'all' && f.key !== 'notes' && <i className={`aclip-dot s-${f.key}`} />}
              {f.label}<span className="filter-count">{counts[f.key]}</span>
            </button>
          ))}
          <span className="assign-label bar-gap">For</span>
          {AUDIO_TEAM.map((person) => (
            <button
              key={person}
              className={`assign-chip${who === person ? ' on' : ''}`}
              style={who === person ? { background: colourOf(person), borderColor: colourOf(person) } : undefined}
              onClick={() => setWho(who === person ? '' : person)}
              title={`Sounds with open notes for ${person}`}
            >
              <i className="swatch" style={{ background: who === person ? 'white' : colourOf(person) }} />
              {person}<span className="filter-count">{counts.people[person]}</span>
            </button>
          ))}
          <button className="next-match" onClick={nextMatch} disabled={filter === 'all' && !who} title="The next sound that matches">next ›</button>
          <span className="spacer" />
          <div className="zoom" title="Zoom (⌘ + scroll on the timeline, or + and -)">
            <button onClick={() => zoomBy(1 / 1.6)} disabled={pps <= minPps + 1e-6}>−</button>
            <input
              type="range" min={0} max={1} step={0.001} value={Number.isFinite(slider) ? slider : 0}
              onChange={(e) => setZoom(minPps * Math.pow(MAX_PPS / minPps, Number(e.target.value)))}
            />
            <button onClick={() => zoomBy(1.6)} disabled={pps >= MAX_PPS - 1e-6}>+</button>
            <button onClick={() => setZoom(null)} title="The whole film (\)">fit</button>
            <span className="zoom-read">{pps < 1 ? `${(1 / pps).toFixed(1)} s/px` : `${Math.round(pps)} px/s`}</span>
          </div>
        </div>

        {doc ? (
          <AudioTimeline
            duration={duration}
            states={states}
            lanes={lanes}
            time={time}
            playing={playing}
            pps={pps}
            minPps={minPps}
            maxPps={MAX_PPS}
            onZoom={setZoom}
            onViewport={setViewW}
            selected={p.slot}
            onSelect={onSelect}
            onSeek={onSeek}
            statusOf={statusOf}
            notesOf={notesOf}
            gainOf={gainOf}
            matches={matches}
            thumbUrl={p.thumbUrl}
            follow={follow}
            reveal={reveal}
          />
        ) : (
          <div className="audio-empty">
            {loading ? 'Loading the sounds…' : (
              <>
                <b>No audio timeline for {release.name}.</b> It is built from the film's cue log with{' '}
                <code>python3 tools/release_audio.py review/public/releases/{release.slug}</code>, and every release cut
                with <code>tools/release_build.py</code> gets one. Releases before 24 September played sound sheets that
                have been re-cut since, so they have none.
              </>
            )}
          </div>
        )}
      </div>
      <div className="panel-col">
        {selected ? (
          <ClipPanel
            release={release}
            releases={p.releases}
            slot={selected}
            clip={active}
            status={statuses.get(selected.key)}
            notes={notesBySlot.get(selected.key) ?? []}
            repliesOf={(id) => p.comments.filter((c) => c.parentId === id)}
            history={p.comments.filter((c) => (c.kind === 'audio_status' || c.kind === 'audio_gain') && c.clip === selected.key).sort((a, b) => b.createdAt.localeCompare(a.createdAt))}
            stateNotes={p.comments.filter((c) => c.releaseId === release.id && c.stateKey === selected.state.key && c.kind === 'comment' && c.topic === 'audio' && !c.clip && !c.parentId)}
            name={p.name}
            onName={p.onName}
            onStatus={setStatus}
            onNote={addNote}
            onReply={reply}
            onDone={p.onDone}
            onEdit={p.onEdit}
            onDelete={p.onDelete}
            onGoTo={onSeek}
            onPickClip={(c) => play(selected, c)}
            gain={selected.layer === 'design' && selected.clips.length ? { db: targetDb(selected.key), film: filmed.get(selected.key) ?? 0, set: gains.get(selected.key) ?? null, draft: draft?.slot === selected.key } : null}
            onGainDraft={draftGain}
            onGain={commitGain}
            onExportGains={exportGains}
            voiceLufs={(() => {
              const v = slots.get(`voice:${selected.state.key}`)?.clips[0]
              return doc && v?.info.lufs != null ? v.info.lufs + filmDb(doc, v) : null
            })()}
            onPrev={() => step(-1)}
            onNext={() => step(1)}
            onClose={deselect}
          />
        ) : (
          <AudioOverview lanes={lanes} statusOf={statusOf} attention={attention} onPick={(s) => play(s, null)} gains={gainSummary} onExportGains={exportGains} />
        )}
      </div>
    </div>
  )
}
