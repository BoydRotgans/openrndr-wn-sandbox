import { useEffect, useRef, useState } from 'react'
import type { Waveform } from '../lib/waveform'
import type { StateInfo } from '../lib/types'

export interface SeekMark {
  at: number
  author: string
  body: string
  done: boolean
}

interface Props {
  state: StateInfo
  fps: number
  time: number
  marks: SeekMark[]
  waveform: Waveform | null
  onScrub(t: number): void
  onStep(frames: number): void
}

/**
 * The current state on its own, frame by frame: a second scrubber that spans only this state,
 * ruled per frame and per second, with the pinned comments on it. Dragging pauses and scrubs;
 * the step buttons move one frame.
 */
export default function StateSeeker({ state, fps, time, marks, waveform, onScrub, onStep }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const bar = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
  const [hover, setHover] = useState<number | null>(null)

  const length = Math.max(state.end - state.start, 1 / fps)
  const frames = Math.round(length * fps)
  const frame = Math.max(0, Math.min(frames, Math.round((time - state.start) * fps)))
  const pct = (t: number) => `${Math.max(0, Math.min(1, (t - state.start) / length)) * 100}%`

  // The state's stretch of the track, drawn once per state and size: a bar per pixel column,
  // the loudest peak in that column, mirrored about the middle.
  useEffect(() => {
    const el = canvas.current
    if (!el) return
    const draw = () => {
      const w = el.clientWidth
      const h = el.clientHeight
      if (!w || !h) return
      const dpr = window.devicePixelRatio || 1
      el.width = Math.round(w * dpr)
      el.height = Math.round(h * dpr)
      const ctx = el.getContext('2d')!
      ctx.scale(dpr, dpr)
      ctx.clearRect(0, 0, w, h)
      if (!waveform) return
      const first = state.start * waveform.rate
      const per = (length * waveform.rate) / w
      ctx.fillStyle = 'rgba(94, 0, 255, 0.28)'
      for (let x = 0; x < w; x++) {
        const a = Math.floor(first + x * per)
        const b = Math.max(a + 1, Math.floor(first + (x + 1) * per))
        let peak = 0
        for (let i = a; i < b && i < waveform.peaks.length; i++) if (waveform.peaks[i] > peak) peak = waveform.peaks[i]
        const bar = Math.max(1, (peak / 255) * (h - 4))
        ctx.fillRect(x, (h - bar) / 2, 1, bar)
      }
    }
    draw()
    const ro = new ResizeObserver(draw)
    ro.observe(el)
    return () => ro.disconnect()
  }, [waveform, state.key, state.start, length])

  const timeAt = (clientX: number) => {
    const r = bar.current!.getBoundingClientRect()
    const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width))
    // snapped to a frame, so a scrub lands where a step would
    const n = Math.round(f * frames)
    return state.start + Math.min(n, frames - 1) / fps
  }

  // rule: a tick per second, and per frame where there is room for it
  const seconds = Math.floor(length)
  const frameTicks = frames <= 600 ? frames : 0
  const hoverFrame = hover != null ? Math.round((hover - state.start) * fps) : null

  return (
    <div className="seeker">
      <button className="seeker-btn" onClick={() => onStep(-1)} title="Back one frame (,)">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor"><rect x="2" y="2" width="2" height="10" /><path d="M12 2v10L5 7z" /></svg>
      </button>
      <div
        className="seeker-bar"
        ref={bar}
        onPointerDown={(e) => {
          dragging.current = true
          e.currentTarget.setPointerCapture(e.pointerId)
          onScrub(timeAt(e.clientX))
        }}
        onPointerMove={(e) => {
          setHover(timeAt(e.clientX))
          if (dragging.current) onScrub(timeAt(e.clientX))
        }}
        onPointerUp={() => { dragging.current = false }}
        onPointerLeave={() => { setHover(null); dragging.current = false }}
      >
        <canvas className="sk-wave" ref={canvas} />
        {frameTicks > 0 &&
          Array.from({ length: frameTicks }, (_, i) => (
            <div key={`f${i}`} className="sk-frame" style={{ left: `${(i / frames) * 100}%` }} />
          ))}
        {Array.from({ length: seconds + 1 }, (_, i) => (
          <div key={`s${i}`} className="sk-second" style={{ left: `${(i / length) * 100}%` }}><span>{i}s</span></div>
        ))}
        <div className="sk-played" style={{ width: pct(time) }} />
        {marks.map((m, i) => (
          <div key={i} className={`sk-mark${m.done ? ' done' : ''}`} style={{ left: pct(m.at) }} title={`${m.author}: ${m.body}`} />
        ))}
        <div className="sk-head" style={{ left: pct(time) }} />
        {hover != null && hoverFrame != null && (
          <div className="sk-hover" style={{ left: pct(hover) }}><span>frame {hoverFrame}</span></div>
        )}
      </div>
      <button className="seeker-btn" onClick={() => onStep(1)} title="Forward one frame (.)">
        <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor"><path d="M2 2v10l7-5z" /><rect x="10" y="2" width="2" height="10" /></svg>
      </button>
      <span className="seeker-frame">frame {frame} / {frames}</span>
    </div>
  )
}
