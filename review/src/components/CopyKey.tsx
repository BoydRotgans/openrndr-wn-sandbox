import { useEffect, useState } from 'react'

/** The state's id and letter, copied to the clipboard on a click — the name the audio files carry. */
export default function CopyKey({ value, big }: { value: string; big?: boolean }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const t = setTimeout(() => setCopied(false), 1400)
    return () => clearTimeout(t)
  }, [copied])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
    } catch {
      const ta = document.createElement('textarea')
      ta.value = value
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      ta.remove()
    }
    setCopied(true)
  }

  return (
    <button className={`copy-key${big ? ' big' : ''}${copied ? ' copied' : ''}`} onClick={copy} title="Copy the state name">
      <span className="copy-key-text">{value}</span>
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><rect x="5.5" y="5.5" width="8" height="8" rx="1.5" /><path d="M10.5 5.5v-2a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2" /></svg>
      <em>{copied ? 'copied' : 'copy'}</em>
    </button>
  )
}
