import { useEffect, useRef, useState } from 'react'
import type { Release, StateInfo } from '../lib/types'
import type { Toast } from './Toasts'

export interface Update {
  state: StateInfo
  count: number
  latestAuthor: string
  latestAt: string
}

interface Props {
  releases: Release[]
  release: Release | null
  onRelease(id: string): void
  updates: Update[]
  onJump(index: number): void
  onMarkAllRead(): void
  name: string
  onName(): void
  onNewRelease(): void
  generalOpen: number
  onGeneral(): void
  tasksOpen: number
  onTasks(): void
  /** Goes up by one each time something arrives: the bell rings for it. */
  ring: number
  /** The last few things anyone did, newest first. */
  activity: Toast[]
  onExport(): void
  mode: 'local' | 'supabase'
}

export default function Header(p: Props) {
  const [open, setOpen] = useState(false)
  const [ringing, setRinging] = useState(false)
  useEffect(() => {
    if (!p.ring) return
    setRinging(true)
    const t = setTimeout(() => setRinging(false), 1800)
    return () => clearTimeout(t)
  }, [p.ring])
  const bell = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (!bell.current?.contains(e.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [open])

  return (
    <header className="header">
      <select className="release-select" value={p.release?.id ?? ''} onChange={(e) => p.onRelease(e.target.value)} disabled={!p.releases.length} title={p.mode === 'local' ? 'Local: comments stay in this browser' : 'Shared through Supabase'}>
        {!p.releases.length && <option value="">no releases</option>}
        {p.releases.map((r) => (
          <option key={r.id} value={r.id}>{r.name} · {r.created}</option>
        ))}
      </select>

      <button className="general-btn" onClick={p.onGeneral} title="Comments on the whole release">
        General comments{p.generalOpen > 0 && <span className="topic-count">{p.generalOpen}</span>}
      </button>
      <button className="general-btn" onClick={p.onTasks} title="Every assigned note in this release, per person">
        Tasks{p.tasksOpen > 0 && <span className="topic-count">{p.tasksOpen}</span>}
      </button>

      {p.release && (
        <a
          className="button download-btn"
          href={p.release.videoUrl}
          download={`wn-review-${p.release.slug}.mp4`}
          title={`Download the video of ${p.release.name}`}
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10" /></svg>
          Download video
        </a>
      )}

      <div className="spacer" />

      <div className="bell-wrap" ref={bell}>
        <button className={`bell${p.updates.length ? ' has' : ''}${ringing ? ' ringing' : ''}`} onClick={() => setOpen((v) => !v)} title="Comments you have not seen yet">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6">
            <path d="M3 11h10l-1.2-1.6V6.5a3.8 3.8 0 0 0-7.6 0v2.9L3 11z" />
            <path d="M6.5 13a1.5 1.5 0 0 0 3 0" />
          </svg>
          {p.updates.length > 0 && <span className="bell-count">{p.updates.length}</span>}
        </button>
        {open && (
          <div className="dropdown">
            <div className="dropdown-head">
              <span>{p.updates.length ? `${p.updates.length} slides with new comments` : 'Nothing unread'}</span>
              {p.updates.length > 0 && <button className="link" onClick={() => { p.onMarkAllRead(); setOpen(false) }}>mark all read</button>}
            </div>
            {p.activity.length > 0 && (
              <>
                <div className="dropdown-section">Recent activity</div>
                {p.activity.map((a) => (
                  <button key={a.id} className={`dropdown-item activity${a.mine ? ' mine' : ''}`} onClick={() => { a.onOpen(); setOpen(false) }}>
                    <span className="act-head"><b>{a.title}</b>{a.at && <span className="act-time">{ago(a.at)}</span>}</span>
                    <span className="act-body">{a.body}</span>
                    <span className="di-meta">{a.where}</span>
                  </button>
                ))}
              </>
            )}
            {p.updates.length > 0 && <div className="dropdown-section">Unread slides</div>}
            {p.updates.map((u) => (
              <button key={u.state.key} className="dropdown-item" onClick={() => { p.onJump(u.state.index); setOpen(false) }}>
                <span className="di-key">{u.state.key}</span>
                <span className="di-meta">{u.count} new · {u.latestAuthor}</span>
              </button>
            ))}

          </div>
        )}
      </div>

      <button className="chip" onClick={p.onName} title="Change your name">{p.name || 'set your name'}</button>
      <button onClick={p.onNewRelease}>+ release</button>
      <button className="primary" onClick={p.onExport}>Export CSV</button>
    </header>
  )
}

/** "just now", "4 min ago", "2 h ago", then the date. */
function ago(iso: string): string {
  const s = (Date.now() - new Date(iso).getTime()) / 1000
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return new Date(iso).toLocaleDateString([], { day: 'numeric', month: 'short' })
}
