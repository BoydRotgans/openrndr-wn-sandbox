import { forwardRef, useEffect, useImperativeHandle, useRef, type ReactNode } from 'react'

/** Where the film stands: not started, loading, running a click, paused by hand, or held at a click's end. */
export type FilmState = 'idle' | 'waiting' | 'playing' | 'paused' | 'held'

export interface FilmHandle {
  /** Plays from `from` and stops on the last frame before `until`, as the show waits for the next click. */
  play(from: number, until: number): void
  /** Shows the frame at `at`, paused, with `until` as the end of the click it belongs to. */
  show(at: number, until: number): void
  /** Pauses a click that is running, or carries it on from where it was paused. */
  toggle(): void
}

interface Props {
  src: string
  /** A soundtrack played over the film, which is then muted. */
  audio: string | null
  /** Whether the film's own sound may be heard at all. */
  filmSound: boolean
  muted: boolean
  onTime(t: number): void
  onState(s: FilmState): void
  onClick(): void
  children?: ReactNode
}

/** How far before a click's end the film is stopped, so the next click's first frame never shows. */
const STOP = 0.06
const HOLD = 0.05

/**
 * The film, one click at a time. The time is followed every animation frame rather than on
 * `timeupdate`, which fires four times a second — too coarse to stop on a click's own end.
 */
const Film = forwardRef<FilmHandle, Props>(function Film({ src, audio, filmSound, muted, onTime, onState, onClick, children }, ref) {
  const video = useRef<HTMLVideoElement>(null)
  const dub = useRef<HTMLAudioElement>(null)
  const until = useRef(Infinity)
  const stopping = useRef(false)

  const whenReady = (go: () => void) => {
    const v = video.current
    if (!v) return
    // before the metadata is in, a currentTime is silently dropped by some browsers
    if (v.readyState >= 1) go()
    else v.addEventListener('loadedmetadata', go, { once: true })
  }

  useImperativeHandle(ref, () => ({
    play(from, end) {
      whenReady(() => {
        const v = video.current!
        until.current = end
        stopping.current = false
        v.currentTime = from
        onTime(from)
        // a seek inside a click that is already running fires no 'playing' of its own
        v.play().then(() => { if (!v.paused) onState('playing') }).catch(() => onState('paused'))
      })
    },
    show(at, end) {
      whenReady(() => {
        const v = video.current!
        until.current = end
        stopping.current = true
        v.pause()
        v.currentTime = at
        onTime(at)
      })
    },
    toggle() {
      const v = video.current
      if (!v) return
      if (v.paused) v.play().catch(() => {})
      else v.pause()
    },
  }))

  // the sound: never the film's own unless the cut says it has no voice in it
  useEffect(() => {
    if (video.current) video.current.muted = muted || !!audio || !filmSound
    if (dub.current) dub.current.muted = muted
  }, [muted, audio, filmSound])

  useEffect(() => {
    const v = video.current
    if (!v) return
    let raf = 0
    const tick = () => {
      const end = until.current
      if (!v.paused && v.currentTime >= end - STOP) {
        stopping.current = true
        v.pause()
        if (v.currentTime > end - HOLD / 2) v.currentTime = end - HOLD
      }
      onTime(v.currentTime)
      raf = requestAnimationFrame(tick)
    }
    const playing = () => {
      stopping.current = false
      onState('playing')
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(tick)
    }
    const paused = () => {
      cancelAnimationFrame(raf)
      onTime(v.currentTime)
      onState(stopping.current ? 'held' : 'paused')
    }
    const waiting = () => onState('waiting')
    const seeked = () => {
      onTime(v.currentTime)
      if (!v.paused) onState('playing')
    }
    v.addEventListener('playing', playing)
    v.addEventListener('pause', paused)
    v.addEventListener('ended', paused)
    v.addEventListener('waiting', waiting)
    v.addEventListener('seeked', seeked)
    return () => {
      cancelAnimationFrame(raf)
      v.removeEventListener('playing', playing)
      v.removeEventListener('pause', paused)
      v.removeEventListener('ended', paused)
      v.removeEventListener('waiting', waiting)
      v.removeEventListener('seeked', seeked)
    }
  }, [src, onTime, onState])

  // The other mix rides on the picture: it follows every play, pause and seek, and any drift past a
  // fifth of a second is pulled back.
  useEffect(() => {
    const v = video.current
    const a = dub.current
    if (!v || !a || !audio) return
    const sync = () => { if (Math.abs(a.currentTime - v.currentTime) > 0.2) a.currentTime = v.currentTime }
    const play = () => { sync(); a.play().catch(() => {}) }
    const stop = () => a.pause()
    v.addEventListener('play', play)
    v.addEventListener('playing', play)
    v.addEventListener('pause', stop)
    v.addEventListener('seeked', sync)
    const drift = setInterval(() => { if (!v.paused) sync() }, 1000)
    return () => {
      v.removeEventListener('play', play)
      v.removeEventListener('playing', play)
      v.removeEventListener('pause', stop)
      v.removeEventListener('seeked', sync)
      clearInterval(drift)
      a.pause()
    }
  }, [audio, src])

  return (
    <div className="film" onClick={onClick}>
      {audio && <audio ref={dub} src={audio} preload="auto" />}
      <video ref={video} src={src} preload="auto" playsInline muted controls={false} disablePictureInPicture />
      {children}
    </div>
  )
})

export default Film
