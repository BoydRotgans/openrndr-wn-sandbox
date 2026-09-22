import { useState } from 'react'
import { ReplyRow } from './Bubble'
import CopyKey from './CopyKey'
import { formatTime } from '../lib/csv'
import { TEAM, colourOf } from '../lib/team'
import type { Comment, Release, StateInfo } from '../lib/types'

interface Props {
  release: Release
  comments: Comment[]
  onGo(state: StateInfo, at: number | null, id: string): void
  onDone(id: string, done: boolean): void
}

/**
 * Every assigned note in the release as a task: filtered per person, open first, each with its
 * conversation, a way to the frame it is about, and a button to close it.
 */
export default function TasksTab({ release, comments, onGo, onDone }: Props) {
  const [who, setWho] = useState('')
  const byKey = new Map(release.manifest.states.map((s) => [s.key, s]))
  const roots = comments.filter((c) => !c.parentId && c.kind === 'comment' && c.assignees && c.assignees.length > 0 && byKey.has(c.stateKey))
  const openFor = (p: string) => roots.filter((c) => !c.done && c.assignees!.includes(p)).length
  const mine = roots
    .filter((c) => !who || c.assignees!.includes(who))
    .sort((a, b) => byKey.get(a.stateKey)!.start - byKey.get(b.stateKey)!.start)
  const open = mine.filter((c) => !c.done)
  const done = mine.filter((c) => c.done)

  const row = (c: Comment) => {
    const s = byKey.get(c.stateKey)!
    const replies = comments.filter((r) => r.parentId === c.id)
    const frame = c.at != null ? Math.round((c.at - s.start) * release.manifest.fps) : null
    return (
      <div key={c.id} className={`task-card${c.done ? ' done' : ''}`}>
        <div className="task-head">
          <CopyKey value={s.key} />
          <span className="task-meta">{s.title} · {formatTime(s.start)}{frame != null ? ` · frame ${frame}` : ''}</span>
          <span className="spacer" />
          {c.assignees!.map((a) => <span key={a} className="assignee" style={{ color: colourOf(a), background: `${colourOf(a)}18` }}>→ {a}</span>)}
        </div>
        <div className="task-body"><b>{c.author}</b> {c.body}</div>
        {replies.length > 0 && (
          <div className="replies">
            {replies.map((r) => <ReplyRow key={r.id} author={r.author} when={r.createdAt} body={r.body} />)}
          </div>
        )}
        <div className="task-actions">
          <button onClick={() => onGo(s, c.at ?? null, c.id)}>▶ Go to the video</button>
          {c.done ? (
            <button onClick={() => onDone(c.id, false)}>Reopen</button>
          ) : (
            <button className="primary" onClick={() => onDone(c.id, true)}>✓ Done</button>
          )}
          {c.done && c.doneBy && <span className="task-meta">done by {c.doneBy}</span>}
        </div>
      </div>
    )
  }

  return (
    <div className="tasks-tab">
      <div className="task-filter">
        <button className={`assign-chip${!who ? ' on' : ''}`} onClick={() => setWho('')}>
          everyone{roots.filter((c) => !c.done).length > 0 && <span className="topic-count">{roots.filter((c) => !c.done).length}</span>}
        </button>
        {TEAM.map((p) => (
          <button
            key={p}
            className={`assign-chip${who === p ? ' on' : ''}`}
            style={who === p ? { background: colourOf(p), borderColor: colourOf(p) } : undefined}
            onClick={() => setWho(who === p ? '' : p)}
          >
            <i className="swatch" style={{ background: who === p ? 'white' : colourOf(p) }} />
            {p}{openFor(p) > 0 && <span className="topic-count">{openFor(p)}</span>}
          </button>
        ))}
      </div>
      <div className="thread-list task-list">
        {open.length === 0 && <div className="empty">Nothing open{who ? ` for ${who}` : ''}.</div>}
        {open.map(row)}
        {done.length > 0 && (
          <details className="tasks-done">
            <summary>{done.length} done</summary>
            {done.map(row)}
          </details>
        )}
      </div>
    </div>
  )
}
