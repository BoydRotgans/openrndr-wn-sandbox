import { useEffect, useRef, useState } from 'react'
import Bubble from './Bubble'
import type { Comment, Release } from '../lib/types'

/** The key general, release-wide comments are stored under instead of a state. */
export const GENERAL = 'general'

interface Props {
  release: Release
  comments: Comment[]
  earlier: { release: Release; comments: Comment[] }[]
  name: string
  onPost(body: string): void
  onDone(id: string, done: boolean): void
  onEdit(id: string, body: string): void
  onDelete(id: string): void
  onReply(id: string, body: string): void
  onClose(): void
}

/** Comments about the release as a whole, not any one state: a panel off the header. */
export default function General({ release, comments, earlier, name, onPost, onDone, onEdit, onDelete, onReply, onClose }: Props) {
  const [draft, setDraft] = useState('')
  const box = useRef<HTMLTextAreaElement>(null)
  useEffect(() => box.current?.focus(), [])

  const send = () => {
    const body = draft.trim()
    if (!body) return
    onPost(body)
    setDraft('')
  }

  const all = [...comments, ...earlier.flatMap((e) => e.comments)]
  const bubble = (c: Comment) => c.parentId ? null : (
    <Bubble key={c.id} c={c} mine={c.author === name} onDone={(d) => onDone(c.id, d)} onEdit={(b) => onEdit(c.id, b)} onDelete={() => onDelete(c.id)} replies={all.filter((r) => r.parentId === c.id)} onReply={(b) => onReply(c.id, b)} onEditReply={onEdit} onDeleteReply={onDelete} compact />
  )

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal general" onClick={(e) => e.stopPropagation()}>
        <div className="general-head">
          <h2>General comments</h2>
          <span className="muted">{release.name}</span>
          <button className="link" onClick={onClose}>close</button>
        </div>
        <div className="composer">
          <textarea
            ref={box}
            value={draft}
            rows={3}
            placeholder="A comment on the whole release…"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                send()
              }
            }}
          />
          <div className="composer-row">
            <button className="primary big" disabled={!draft.trim()} onClick={send}>Add comment</button>
          </div>
        </div>
        <div className="thread-list general-list">
          {comments.filter((c) => !c.parentId).length === 0 && <div className="empty">No general comments yet.</div>}
          {comments.map(bubble)}
          {earlier.map((e) => (
            <div key={e.release.id} className="earlier-release">
              <div className="earlier-name">{e.release.name}</div>
              {e.comments.map(bubble)}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
