/** What this browser remembers about its visitor: their name and what they have seen. */
const NAME = 'wn-review.name'
const SEEN = 'wn-review.seen.v1'
const LOOP = 'wn-review.loop'
const MUTED = 'wn-review.muted'
const KEY = 'wn-review.key'

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* private window, blocked storage: the page still works, it just forgets */
  }
}

export const prefs = {
  getName: () => read<string>(NAME, ''),
  setName: (name: string) => write(NAME, name),
  getSeen: () => read<Record<string, string>>(SEEN, {}),
  setSeen: (seen: Record<string, string>) => write(SEEN, seen),
  getLoop: () => read<boolean>(LOOP, false),
  setLoop: (on: boolean) => write(LOOP, on),
  getKey: () => read<string>(KEY, ''),
  setKey: (k: string) => write(KEY, k),
  getMuted: () => read<boolean>(MUTED, false),
  setMuted: (on: boolean) => write(MUTED, on),
}

export const seenKey = (releaseId: string, stateKey: string) => `${releaseId}/${stateKey}`
