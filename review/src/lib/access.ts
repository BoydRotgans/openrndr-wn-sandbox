import { prefs } from './prefs'

/**
 * The site has two doors, each opened by a private link: `?k=<key>` once, then the key is
 * remembered in the browser and the address is cleaned.
 *
 * - **review** (`/`) opens with the team's key.
 * - **practice** (`/practice`) opens with the client's key, and with the team's, so the team can see
 *   what the client sees. The client's key opens nothing else.
 *
 * **Only hashes are in the page.** `VITE_ACCESS_KEY_SHA256` and `VITE_PRACTICE_KEY_SHA256` are the
 * SHA-256 of each key in hex, so reading the page's code gives neither key away — in particular the
 * client, who has the practice page's code, cannot find the review's key in it. A plain key in a
 * `VITE_` variable would be written into the bundle, which is why no code here reads one.
 *
 * It keeps the *link* private, not the data secret: the check is in the page, and the films and
 * the Supabase anon key are reachable by anyone who knows where to look.
 *
 * With no hash set the door is open under `vite` (local work) and shut in a build, so a deploy
 * that lost its variables fails closed rather than open.
 */
export type Door = 'review' | 'practice'

const REVIEW = clean(import.meta.env.VITE_ACCESS_KEY_SHA256 as string | undefined)
const PRACTICE = clean(import.meta.env.VITE_PRACTICE_KEY_SHA256 as string | undefined)

function clean(v: string | undefined) {
  return v?.trim().toLowerCase() || ''
}

/**
 * The practice key sealed with the review key (AES-GCM, base64 of iv + ciphertext + tag), so the
 * practice page's Share can hand its link to a team member who came in on the review key — without
 * the practice key being readable in the page. See scripts/seal-practice-key.mjs.
 */
const SEALED = (import.meta.env.VITE_PRACTICE_KEY_SEALED as string | undefined)?.trim() || ''

async function sha256(text: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * The practice page's own link, to share: `/practice?k=<practice key>`, and never the review key,
 * whichever of the two opened the page. Null when the page holds neither the practice key nor a
 * review key that can open the sealed copy of it.
 */
export async function practiceLink(): Promise<string | null> {
  const link = (key: string) => `${location.origin}/practice?k=${encodeURIComponent(key)}`
  // either slot may hold either key: a team member can open this page on /practice?k=<review key>
  const held = [prefs.getPracticeKey(), prefs.getKey()].filter(Boolean)
  const hashes = await Promise.all(held.map(sha256))
  const own = held.find((_, i) => PRACTICE && hashes[i] === PRACTICE)
  if (own) return link(own)
  const review = held.find((_, i) => REVIEW && hashes[i] === REVIEW)
  if (!review || !SEALED) return null
  try {
    // A key of its own, not the published hash of the review key, which anyone can read.
    const raw = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`wn-practice-share:${review}`))
    const key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt'])
    const bytes = Uint8Array.from(atob(SEALED), (c) => c.charCodeAt(0))
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12) }, key, bytes.slice(12))
    const practice = new TextDecoder().decode(plain)
    return PRACTICE && (await sha256(practice)) === PRACTICE ? link(practice) : null
  } catch {
    return null
  }
}

export async function hasAccess(door: Door): Promise<boolean> {
  const url = new URL(location.href)
  const given = url.searchParams.get('k')
  if (given) {
    if (door === 'review') prefs.setKey(given)
    else prefs.setPracticeKey(given)
    url.searchParams.delete('k')
    history.replaceState(null, '', url.pathname + url.search + url.hash)
  }
  const wanted = (door === 'review' ? [REVIEW] : [PRACTICE, REVIEW]).filter(Boolean)
  if (!wanted.length) return import.meta.env.DEV
  // a team member who has opened the review can open the practice page without a second link
  const held = door === 'review' ? [prefs.getKey()] : [prefs.getPracticeKey(), prefs.getKey()]
  if (!crypto?.subtle) return false
  for (const key of held.filter(Boolean)) {
    if (wanted.includes(await sha256(key))) return true
  }
  return false
}
