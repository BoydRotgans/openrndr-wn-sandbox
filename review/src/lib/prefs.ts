/** What this browser remembers about its visitor: their name and what they have seen. */
const NAME = 'wn-review.name'
const SEEN = 'wn-review.seen.v1'
const LOOP = 'wn-review.loop'
const MUTED = 'wn-review.muted'
const TRACK = 'wn-review.track'
const VOICE_TRACK = 'wn-review.voiceTrack'
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
  /** Which soundtrack was last chosen: `film` is the one in the video. */
  getTrack: () => read<string>(TRACK, 'film'),
  setTrack: (key: string) => write(TRACK, key),
  /** Which subtitle track the voice-over panel was last left on. */
  getVoiceTrack: () => read<string>(VOICE_TRACK, 'voiceover'),
  setVoiceTrack: (key: string) => write(VOICE_TRACK, key),
}

export const seenKey = (releaseId: string, stateKey: string) => `${releaseId}/${stateKey}`
