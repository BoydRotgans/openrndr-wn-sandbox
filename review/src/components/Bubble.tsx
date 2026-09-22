import { useState } from 'react'
import type { Comment } from '../lib/types'
import { colourOf } from '../lib/team'

export function when(iso: string) {
  const d = new Date(iso)
  const today = new Date()
  const sameDay = d.toDateString() === today.toDateString()
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  return sameDay ? time : `${d.toLocaleDateString([], { day: 'numeric', month: 'short' })} ${time}`
}

interface Props {
  c: Comment
  mine: boolean
  onDone(done: boolean): void
  onEdit?(body: string): void
  onDelete?(): void
  /** What to show for the pin, and where to go when it is clicked. */
  pinLabel?: string
  onPin?(): void
  /** The answers under this note, and how to add one. */
  replies?: Comment[]
  onReply?(body: string): void
  onEditReply?(id: string, body: string): void
  onDeleteReply?(id: string): void
  compact?: boolean
}

/** One answer under a note: who, when, the text, and edit / delete on hover. */
export function ReplyRow({ author, when: at, body, onEdit, onDelete }: {
  author: string
  when?: string
  body: string
  onEdit?(body: string): void
  onDelete?(): void
}) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(body)
  const save = () => {
    const t = text.trim()
    if (t && t !== body) onEdit?.(t)
    setEditing(false)
  }
  return (
    <div className="reply">
      <span className="avatar small" aria-hidden="true">{(author || '?').trim().charAt(0).toUpperCase()}</span>
      <div className="reply-text">
        <b>{author}</b>
        {at && <span className="time">{when(at)}</span>}
        {!editing && (onEdit || onDelete) && (
          <span className="bubble-actions">
            {onEdit && <button className="link" onClick={() => { setText(body); setEditing(true) }}>edit</button>}
            {onDelete && <button className="link" onClick={onDelete}>delete</button>}
          </span>
        )}
        {editing ? (
          <div className="reply-box">
            <input
              autoFocus
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); save() }
                if (e.key === 'Escape') setEditing(false)
              }}
            />
            <button className="primary" disabled={!text.trim()} onClick={save}>Save</button>
          </div>
        ) : (
          <div>{body}</div>
        )}
      </div>
    </div>
  )
}

/** One comment: who, when, the text, a done tick, and edit / delete on hover. */
export default function Bubble({ c, mine, onDone, onEdit, onDelete, pinLabel, onPin, replies, onReply, onEditReply, onDeleteReply, compact }: Props) {
  const [reply, setReply] = useState('')
  const sendReply = () => {
    const body = reply.trim()
    if (body) onReply?.(body)
    setReply('')
  }
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(c.body)

  const save = () => {
    const body = text.trim()
    if (body && body !== c.body) onEdit?.(body)
    setEditing(false)
  }

  return (
    <div className={`bubble${mine ? ' mine' : ''}${c.done ? ' done' : ''}${compact ? ' compact' : ''}`}>
      <div className="bubble-head">
        <span className="avatar" aria-hidden="true">{(c.author || '?').trim().charAt(0).toUpperCase()}</span>
        <span className="author">{c.author}</span>
        <span className="time">{when(c.createdAt)}{c.editedAt ? ' · edited' : ''}</span>
        {!editing && (onEdit || onDelete) && (
          <span className="bubble-actions">
            {onEdit && <button className="link" onClick={() => { setText(c.body); setEditing(true) }}>edit</button>}
            {onDelete && <button className="link" onClick={onDelete}>delete</button>}
          </span>
        )}
        <label className="done-tick" title={c.done ? `Done by ${c.doneBy ?? ''}${c.doneAt ? `, ${when(c.doneAt)}` : ''}` : 'Mark as done'}>
          <input type="checkbox" checked={c.done} onChange={(e) => onDone(e.target.checked)} />
        </label>
      </div>
      {c.kind === 'voiceover' && <div className="bubble-kind">voice-over update</div>}
      {c.kind === 'voiceover_extended' && <div className="bubble-kind">extended voice-over update</div>}
      {c.assignees && c.assignees.length > 0 && (
        <div className="assignees">{c.assignees.map((a) => <span key={a} className="assignee" style={{ color: colourOf(a), background: `${colourOf(a)}18` }}>→ {a}</span>)}</div>
      )}
      {c.at != null && (
        <button className="pin-chip" onClick={onPin} title="Go to this frame">
          <svg width="10" height="10" viewBox="0 0 10 10" fill="currentColor"><circle cx="5" cy="5" r="4" /></svg>
          {pinLabel ?? `${c.at.toFixed(2)}s`}
          {c.x != null && c.y != null ? ` · ${Math.round(c.x * 100)}%, ${Math.round(c.y * 100)}%` : ''}
        </button>
      )}
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
        <div className="bubble-body">{c.body}</div>
      )}
      {replies && replies.length > 0 && (
        <div className="replies">
          {replies.map((r) => (
            <ReplyRow
              key={r.id}
              author={r.author}
              when={r.createdAt}
              body={r.body}
              onEdit={onEditReply ? (b) => onEditReply(r.id, b) : undefined}
              onDelete={onDeleteReply ? () => onDeleteReply(r.id) : undefined}
            />
          ))}
        </div>
      )}
      {onReply && !editing && (
        <div className="reply-box always">
          <input
            value={reply}
            placeholder="Reply…"
            onChange={(e) => setReply(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { e.preventDefault(); sendReply() }
            }}
          />
          {reply.trim() && <button className="primary" onClick={sendReply}>Send</button>}
        </div>
      )}
    </div>
  )
}
