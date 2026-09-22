export interface Toast {
  id: string
  title: string
  body: string
  where: string
  mine: boolean
  /** When it happened, ISO. */
  at?: string
  onOpen(): void
}

/** What others just did, top right, a few at a time; each goes by itself after a while. */
export default function Toasts({ toasts, onClose }: { toasts: Toast[]; onClose(id: string): void }) {
  return (
    <div className="toasts">
      {toasts.map((t) => (
        <div key={t.id} className={`toast${t.mine ? ' mine' : ''}`} onClick={() => { t.onOpen(); onClose(t.id) }}>
          <div className="toast-head">
            <b>{t.title}</b>
            <button className="link" onClick={(e) => { e.stopPropagation(); onClose(t.id) }} aria-label="Dismiss">✕</button>
          </div>
          <div className="toast-body">{t.body}</div>
          {t.where && <div className="toast-where">{t.where}</div>}
        </div>
      ))}
    </div>
  )
}
