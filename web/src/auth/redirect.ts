/** `/login?next=<path>`; slashes stay readable, everything else that could break a query string is escaped. */
export function loginPath(next: string): string {
  return `/login?next=${encodeURIComponent(next).replace(/%2F/g, '/')}`
}

/**
 * Only same-origin paths are honoured after login; anything else goes home. The raw value is rejected outright if it
 * holds a control character or a backslash (the URL parser silently drops tabs and newlines and treats `\` as `/`, so
 * `/\t/evil.example` would otherwise become `//evil.example`), then it must parse to this origin.
 */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith('/')) return '/'
  for (const ch of raw) {
    const code = ch.codePointAt(0) as number
    if (code < 0x20 || code === 0x7f || ch === '\\') return '/'
  }
  try {
    const url = new URL(raw, window.location.origin)
    if (url.origin !== window.location.origin) return '/'
    // Dot segments are normalised away by the parser, so "/.//evil.example" parses same-origin but its pathname is
    // "//evil.example": a protocol-relative URL to another host. Never hand that back.
    if (url.pathname.startsWith('//')) return '/'
    return url.pathname + url.search + url.hash
  } catch {
    return '/'
  }
}
