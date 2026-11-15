/** `/login?next=<path>`; slashes stay readable, everything else that could break a query string is escaped. */
export function loginPath(next: string): string {
  return `/login?next=${encodeURIComponent(next).replace(/%2F/g, '/')}`
}

/** Only same-site absolute paths are honoured after login; anything else (https://…, //host, javascript:) goes home. */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return '/'
  return raw
}
