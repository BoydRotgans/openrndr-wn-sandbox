import { forwardRef, useEffect, useImperativeHandle, useRef, useState, type ReactNode } from 'react'
import { prefs } from '../lib/prefs'
import { ReplyRow } from './Bubble'

export interface PlayerHandle {
  seek(t: number, play?: boolean): void
  play(): void
  pause(): void
  toggle(): void
  /** Pauses and moves the playhead by whole frames. */
  step(frames: number): void
}

/** A comment standing on the picture. */
export interface Dot {
  id: string
  x: number
  y: number
  author: string
  body: string
  done: boolean
  n: number
  replies: { id: string; author: string; body: string }[]
}

interface Props {
  src: string
  /** The state to keep the playhead inside while looping; null plays on. */
  loopRange: { start: number; end: number } | null
  onTime(t: number): void
  onPlaying(playing: boolean): void
  /** Shows the paused mark over the picture. */
  paused?: boolean
  fps: number
  /** The comment tool: a click on the picture drops a note. Off, a click plays and pauses. */
  commenting: boolean
  onCommenting(on: boolean): void
  /** The note being written, and the box to write it in. */
  pending?: { x: number; y: number } | null
  pendingBox?: ReactNode
  onPlace?(x: number, y: number): void
  /** The pending note dragged to another spot while it is being written. */
  onMove?(x: number, y: number): void
  dots: Dot[]
  onDotDone?(id: string, done: boolean): void
  onDotEdit?(id: string, body: string): void
  onDotDelete?(id: string): void
  onDotReply?(id: string, body: string): void
  onReplyEdit?(id: string, body: string): void
  onReplyDelete?(id: string): void
  /** The note just jumped to: held open and pulsed. `n` changes on every jump, so the same note can be jumped to twice. */
  focus?: { id: string; n: number } | null
}

/**
 * The film. Time is reported every animation frame while playing rather than on `timeupdate`,
 * which fires four times a second — too coarse for a loop to turn on the state's own end.
 */
const Player = forwardRef<PlayerHandle, Props>(function Player({ src, loopRange, onTime, onPlaying, paused, fps, commenting, onCommenting, pending, pendingBox, onPlace, onMove, dots, onDotDone, onDotEdit, onDotDelete, onDotReply, onReplyEdit, onReplyDelete, focus }, ref) {
  const video = useRef<HTMLVideoElement>(null)
  const frame = useRef<HTMLDivElement>(null)
  const [fullscreen, setFullscreen] = useState(false)
  useEffect(() => {
    const onChange = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else frame.current?.requestFullscreen().catch(() => {})
  }
  const range = useRef(loopRange)
  range.current = loopRange
  const [muted, setMuted] = useState(prefs.getMuted())
  // the note held open by a click on its dot
  const [openDot, setOpenDot] = useState<string | null>(null)
  const [pulsing, setPulsing] = useState<string | null>(null)
  useEffect(() => {
    if (!focus) return
    setOpenDot(focus.id)
    setPulsing(focus.id)
    const t = setTimeout(() => setPulsing(null), 3600)
    return () => clearTimeout(t)
  }, [focus])
  const dragging = useRef(false)
  useEffect(() => {
    if (video.current) video.current.muted = muted
    prefs.setMuted(muted)
  }, [muted, src])

  useImperativeHandle(ref, () => ({
    seek(t, play = true) {
      const v = video.current
      if (!v) return
      const go = () => {
        v.currentTime = t
        onTime(t)
        if (play) v.play().catch(() => {})
      }
      // before the metadata is in, a currentTime is silently dropped by some browsers
      if (v.readyState >= 1) go()
      else v.addEventListener('loadedmetadata', go, { once: true })
    },
    play: () => video.current?.play().catch(() => {}),
    step(frames) {
      const v = video.current
      if (!v) return
      v.pause()
      // a hair past the frame's own start, so the decoder shows that frame and not the one before
      const at = Math.round(v.currentTime * fps + frames) / fps + 0.0005
      v.currentTime = Math.max(0, Math.min(v.duration || at, at))
      onTime(v.currentTime)
    },
    pause: () => video.current?.pause(),
    toggle() {
      const v = video.current
      if (!v) return
      if (v.paused) v.play().catch(() => {})
      else v.pause()
    },
  }))

  useEffect(() => {
    const v = video.current
    if (!v) return
    let raf = 0
    const tick = () => {
      const r = range.current
      if (r && !v.paused && v.currentTime >= r.end - 0.04) {
        v.currentTime = r.start
      }
      onTime(v.currentTime)
      raf = requestAnimationFrame(tick)
    }
    const start = () => {
      onPlaying(true)
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(tick)
    }
    const stop = () => {
      onPlaying(false)
      cancelAnimationFrame(raf)
      onTime(v.currentTime)
    }
    const seeked = () => onTime(v.currentTime)
    v.addEventListener('play', start)
    v.addEventListener('pause', stop)
    v.addEventListener('ended', stop)
    v.addEventListener('seeked', seeked)
    return () => {
      cancelAnimationFrame(raf)
      v.removeEventListener('play', start)
      v.removeEventListener('pause', stop)
      v.removeEventListener('ended', stop)
      v.removeEventListener('seeked', seeked)
    }
  }, [src, onTime, onPlaying])

  return (
    <div ref={frame} className={`player${paused ? ' paused' : ''}${commenting ? ' commenting' : ''}`}>
      <div className="paused-mark" aria-hidden="true">
        <svg className="glyph-pause" width="26" height="26" viewBox="0 0 26 26" fill="currentColor"><rect x="5" y="4" width="6" height="18" rx="1" /><rect x="15" y="4" width="6" height="18" rx="1" /></svg>
        <svg className="glyph-play" width="26" height="26" viewBox="0 0 26 26" fill="currentColor"><path d="M8 4.5v17l14-8.5z" /></svg>
      </div>
      <video ref={video} src={src} preload="auto" playsInline controls={false} muted={muted} onClick={(e) => {
        const v = video.current
        if (!v) return
        if (commenting && onPlace) {
          const { x, y } = spot(v, e.clientX, e.clientY)
          v.pause()
          onPlace(x, y)
          return
        }
        if (v.paused) v.play().catch(() => {})
        else v.pause()
      }} />
      <div className="dots">
        {dots.map((d) => (
          <div key={d.id} className={`dot-pin${d.done ? ' done' : ''}${openDot === d.id ? ' open' : ''}${pulsing === d.id ? ' focus' : ''}${d.x > 0.6 ? ' flip' : ''}${d.y > 0.55 ? ' up' : ''}`} style={place(video.current, d.x, d.y)}>
            <span className="dot-head" onClick={(e) => { e.stopPropagation(); setOpenDot(openDot === d.id ? null : d.id) }}>
              {d.author.trim().charAt(0).toUpperCase() || d.n}
            </span>
            <DotCard
              d={d}
              open={openDot === d.id}
              onDone={(done) => onDotDone?.(d.id, done)}
              onEdit={(body) => onDotEdit?.(d.id, body)}
              onDelete={() => { setOpenDot(null); onDotDelete?.(d.id) }}
              onReply={(body) => onDotReply?.(d.id, body)}
              onReplyEdit={(id, body) => onReplyEdit?.(id, body)}
              onReplyDelete={(id) => onReplyDelete?.(id)}
              onClose={() => setOpenDot(null)}
            />
          </div>
        ))}
        {pending && (
          <div className={`dot-pin pending${pending.x > 0.6 ? ' flip' : ''}${pending.y > 0.55 ? ' up' : ''}`} style={place(video.current, pending.x, pending.y)}>
            <span
              className="dot-head marker"
              title="Drag to move the note"
              onPointerDown={(e) => {
                e.stopPropagation()
                e.currentTarget.setPointerCapture(e.pointerId)
                dragging.current = true
              }}
              onPointerMove={(e) => {
                if (!dragging.current || !video.current || !onMove) return
                const { x, y } = spot(video.current, e.clientX, e.clientY)
                onMove(x, y)
              }}
              onPointerUp={(e) => { dragging.current = false; e.currentTarget.releasePointerCapture(e.pointerId) }}
              onClick={(e) => e.stopPropagation()}
            >
              <svg width="16" height="16" viewBox="0 0 18 18" fill="white" stroke="white" strokeWidth="1" strokeLinejoin="round"><path d="M3 4.5A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v6a1.5 1.5 0 0 1-1.5 1.5H8l-4 3.5V12H4.5A1.5 1.5 0 0 1 3 10.5z" /></svg>
            </span>
            {pendingBox}
          </div>
        )}
      </div>
      <div className="player-bar">
        <button className={`player-btn tool${commenting ? ' on' : ''}`} onClick={() => onCommenting(!commenting)} title={commenting ? 'Comment tool on: click the picture to add a note (C)' : 'Comment tool off: click the picture to play or pause (C)'}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"><path d="M3 4.5A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v6a1.5 1.5 0 0 1-1.5 1.5H8l-4 3.5V12H4.5A1.5 1.5 0 0 1 3 10.5z" /></svg>
        </button>
        <button className="player-btn" onClick={toggleFullscreen} title={fullscreen ? 'Exit full screen' : 'Full screen'}>
          {fullscreen ? (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M7 3v4H3M11 3v4h4M7 15v-4H3M11 15v-4h4" /></svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7V3h4M15 7V3h-4M3 11v4h4M15 11v4h-4" /></svg>
          )}
        </button>
        <button className="player-btn" onClick={() => setMuted((m) => !m)} title={muted ? 'Unmute' : 'Mute'}>
          {muted ? (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.5h3l4-3v11l-4-3h-3z" /><path d="M12 6.5l4 5M16 6.5l-4 5" /></svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M2.5 6.5h3l4-3v11l-4-3h-3z" /><path d="M12.5 6a4.5 4.5 0 0 1 0 6M14.5 4a7 7 0 0 1 0 10" /></svg>
          )}
        </button>
      </div>
    </div>
  )
})

export default Player

/** Where the picture really is inside the element: letterboxed in full screen, flush otherwise. */
function contentRect(v: HTMLVideoElement) {
  const r = v.getBoundingClientRect()
  const vw = v.videoWidth || 32
  const vh = v.videoHeight || 9
  const scale = Math.min(r.width / vw, r.height / vh)
  const w = vw * scale
  const h = vh * scale
  return { left: r.left + (r.width - w) / 2, top: r.top + (r.height - h) / 2, width: w, height: h, box: r }
}

function spot(v: HTMLVideoElement, clientX: number, clientY: number) {
  const c = contentRect(v)
  return {
    x: Math.min(1, Math.max(0, (clientX - c.left) / c.width)),
    y: Math.min(1, Math.max(0, (clientY - c.top) / c.height)),
  }
}

/** A dot's place as a percentage of the element, from its place on the picture. */
function place(v: HTMLVideoElement | null, x: number, y: number) {
  if (!v) return { left: `${x * 100}%`, top: `${y * 100}%` }
  const c = contentRect(v)
  return {
    left: `${((c.left - c.box.left + x * c.width) / c.box.width) * 100}%`,
    top: `${((c.top - c.box.top + y * c.height) / c.box.height) * 100}%`,
  }
}

/** The note itself: read on hover; held open by a click, with the done tick, edit and delete. */
function DotCard({ d, open, onDone, onEdit, onDelete, onReply, onReplyEdit, onReplyDelete, onClose }: {
  d: Dot
  open: boolean
  onDone(done: boolean): void
  onEdit(body: string): void
  onDelete(): void
  onReply(body: string): void
  onReplyEdit(id: string, body: string): void
  onReplyDelete(id: string): void
  onClose(): void
}) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(d.body)
  const [reply, setReply] = useState('')
  const sendReply = () => {
    const body = reply.trim()
    if (body) onReply(body)
    setReply('')
  }
  useEffect(() => { if (!open) setEditing(false) }, [open])
  const save = () => {
    const body = text.trim()
    if (body && body !== d.body) onEdit(body)
    setEditing(false)
  }
  return (
    <div className="dot-card" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()}>
      <div className="dot-card-head">
        <b>{d.author}</b>
        {open && (
          <label className="done-tick" title={d.done ? 'Done' : 'Mark as done'}>
            <input type="checkbox" checked={d.done} onChange={(e) => onDone(e.target.checked)} />
          </label>
        )}
      </div>
      {editing ? (
        <div className="bubble-edit">
          <textarea
            autoFocus
            value={text}
            rows={3}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); save() }
              if (e.key === 'Escape') setEditing(false)
            }}
          />
          <div className="composer-row">
            <button onClick={() => setEditing(false)}>Cancel</button>
            <button className="primary" disabled={!text.trim()} onClick={save}>Save</button>
          </div>
        </div>
      ) : (
        <div>{d.body}</div>
      )}
      {d.replies.length > 0 && (
        <div className="replies">
          {d.replies.map((r) => (
            <ReplyRow
              key={r.id}
              author={r.author}
              body={r.body}
              onEdit={open ? (b) => onReplyEdit(r.id, b) : undefined}
              onDelete={open ? () => onReplyDelete(r.id) : undefined}
            />
          ))}
        </div>
      )}
      {open && !editing && (
        <div className="reply-box">
          <input
            value={reply}
            placeholder="Reply…"
            onChange={(e) => setReply(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); sendReply() } }}
          />
          <button className="primary" disabled={!reply.trim()} onClick={sendReply}>Send</button>
        </div>
      )}
      {open && !editing && (
        <div className="dot-actions">
          <button className="link" onClick={() => { setText(d.body); setEditing(true) }}>edit</button>
          <button className="link" onClick={onDelete}>delete</button>
          <button className="link" onClick={onClose}>close</button>
        </div>
      )}
    </div>
  )
}
