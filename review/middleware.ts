import { next } from '@vercel/functions/middleware'
import { forRehearsal, keyCookie, roleOf, roleOfKey } from './server/gate.js'

/**
 * The site's gate, on Vercel rather than in the page, so nothing of the review reaches anyone without the
 * team's key: not the page, not its code, not a release's film or manifest. The client's key reaches the
 * rehearsal page and what it plays and nothing else (server/gate.ts says which paths those are).
 *
 * A key in the address is checked and, if good, kept as a cookie, and the request goes on with the `?k=`
 * still in it, so the page stores the key as before. Without a good cookie, a page request gets a small
 * unlock page and anything else a 404. The unlock page reads a key this browser already holds — every
 * team member's browser has one from before the gate — and comes back with it in the address, so an old
 * link or a bookmark opens with no second step.
 */
export default async function middleware(request: Request) {
  const url = new URL(request.url)
  const path = url.pathname
  const given = url.searchParams.get('k')
  const fresh = given ? await roleOfKey(given) : null
  const role = fresh === 'admin' ? 'admin' : (await roleOf(request)) ?? fresh
  const rehearsalPath = forRehearsal(path)

  if (role === 'admin' || (role === 'rehearsal' && rehearsalPath)) {
    return fresh && given ? next({ headers: [['Set-Cookie', keyCookie(fresh, given)]] }) : next()
  }
  if (path.startsWith('/api/')) {
    return new Response('{"error":"private"}', { status: 401, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } })
  }
  if (path === '/' || path.endsWith('.html') || !/\.[a-z0-9]+$/i.test(path)) {
    return new Response(unlockPage(rehearsalPath), {
      status: 401,
      headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-robots-tag': 'noindex, nofollow' },
    })
  }
  return new Response('Not found', { status: 404, headers: { 'cache-control': 'no-store' } })
}

/**
 * Shut, with one try at opening: a key this browser keeps from an earlier visit is put in the address
 * once. A key that does not open this page, or none at all, leaves the message standing.
 */
function unlockPage(rehearsal: boolean) {
  const slots = rehearsal ? ['wn-practice.key', 'wn-review.key'] : ['wn-review.key']
  const text = rehearsal
    ? '<h1>Deze pagina is privé.</h1><p>Open hem met de link die je hebt ontvangen.</p><p class="en">This page is private. Open it with the link you were sent.</p>'
    : '<h1>This review is private.</h1><p>Open it with the link you were sent.</p>'
  return `<!doctype html>
<html lang="${rehearsal ? 'nl' : 'en'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow"><title>${rehearsal ? 'Privé' : 'Private'}</title>
<style>
  html, body { height: 100%; margin: 0; }
  body { display: grid; place-content: center; text-align: center; padding: 24px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: ${rehearsal ? '#0d0e10' : '#f4f4f2'}; color: ${rehearsal ? '#f1f1ef' : '#1c1c1c'}; }
  h1 { font-size: 24px; margin: 0 0 8px; } p { margin: 0; color: ${rehearsal ? '#9095a0' : '#6b6f75'}; } p.en { margin-top: 14px; font-size: 13px; }
</style></head>
<body><main id="m" hidden>${text}</main>
<script>
(function () {
  var slots = ${JSON.stringify(slots)};
  var url = new URL(location.href);
  if (!url.searchParams.get('k')) {
    for (var i = 0; i < slots.length; i++) {
      try {
        var key = JSON.parse(localStorage.getItem(slots[i]) || '""');
        if (key) { url.searchParams.set('k', key); location.replace(url.toString()); return; }
      } catch (e) {}
    }
  }
  document.getElementById('m').hidden = false;
})();
</script></body></html>`
}
