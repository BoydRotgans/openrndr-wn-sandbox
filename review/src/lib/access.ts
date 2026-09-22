import { prefs } from './prefs'

/**
 * The site is reached by a private link: `?k=<key>` once, then the key is remembered in the
 * browser and the address is cleaned. With no VITE_ACCESS_KEY set (local runs) it is open.
 * The check is in the page, so it keeps the link private rather than the data secret: anyone
 * who has the link has the site.
 */
export function hasAccess(): boolean {
  const wanted = (import.meta.env.VITE_ACCESS_KEY as string | undefined)?.trim()
  if (!wanted) return true
  const url = new URL(location.href)
  const given = url.searchParams.get('k')
  if (given) {
    prefs.setKey(given)
    url.searchParams.delete('k')
    history.replaceState(null, '', url.pathname + url.search + url.hash)
  }
  return prefs.getKey() === wanted
}
