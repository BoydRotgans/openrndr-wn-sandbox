/**
 * Who a request is from, decided on Vercel's side rather than in the page: the middleware and the notes
 * function both ask here. There are two keys, the team's (the review, and everything else) and the
 * client's (the rehearsal page alone). A key arrives once as `?k=` and is then carried as an HttpOnly
 * cookie holding the key itself — never its hash, since the hashes are published in the page's code and
 * a cookie holding one could be forged from it.
 */
export const ADMIN_COOKIE = 'wn_admin'
export const REHEARSAL_COOKIE = 'wn_rehearsal'

const YEAR = 60 * 60 * 24 * 365

export type Role = 'admin' | 'rehearsal' | null

const hash = (name: string) => (process.env[name] ?? '').trim().toLowerCase()

export async function sha256(text: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('')
}

export function cookies(request: Request): Record<string, string> {
  const out: Record<string, string> = {}
  for (const part of (request.headers.get('cookie') ?? '').split(';')) {
    const i = part.indexOf('=')
    if (i < 0) continue
    try {
      out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim())
    } catch {
      /* a cookie that is not ours */
    }
  }
  return out
}

/** Which role a key opens, if any. With a hash missing nothing opens: a deploy that lost its variables fails closed. */
export async function roleOfKey(key: string | null | undefined): Promise<Role> {
  const admin = hash('VITE_ACCESS_KEY_SHA256')
  const rehearsal = hash('VITE_PRACTICE_KEY_SHA256')
  if (!key || !admin || !rehearsal) return null
  const h = await sha256(key)
  return h === admin ? 'admin' : h === rehearsal ? 'rehearsal' : null
}

/** The role the request's cookies carry; the team's key wins over the client's. */
export async function roleOf(request: Request): Promise<Role> {
  const jar = cookies(request)
  if ((await roleOfKey(jar[ADMIN_COOKIE])) === 'admin') return 'admin'
  if ((await roleOfKey(jar[REHEARSAL_COOKIE])) === 'rehearsal') return 'rehearsal'
  return null
}

export function keyCookie(role: 'admin' | 'rehearsal', key: string) {
  const name = role === 'admin' ? ADMIN_COOKIE : REHEARSAL_COOKIE
  return `${name}=${encodeURIComponent(key)}; Path=/; Max-Age=${YEAR}; HttpOnly; Secure; SameSite=Lax`
}

/**
 * What the client's key may reach: the rehearsal page, its own code, its film, and its notes. Everything
 * else on the site — the review, its code, every review release, the release list — is the team's.
 */
export function forRehearsal(path: string) {
  return (
    /^\/practice(\.html)?\/?$/.test(path) ||
    path.startsWith('/assets/r/') ||
    path === '/releases/practice.json' ||
    path.startsWith('/releases/practice/') ||
    path === '/api/notes'
  )
}
