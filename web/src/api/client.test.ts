import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError, api, setUnauthorizedHandler } from './client'
import { clearSession, getSession } from '../auth/session'
import { signIn } from '../test-utils'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(null)
  clearSession()
  sessionStorage.clear()
})

function problemResponse(status: number, slug = 'x', extra: Record<string, unknown> = {}, headers = {}) {
  return new Response(
    JSON.stringify({ type: `https://surge.dev/problems/${slug}`, title: slug, status, ...extra }),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )
}

it('sends the bearer, never X-User-Id, and parses problems', async () => {
  signIn(['USER'], 60_000, 'tok-9')
  fetchMock.mockResolvedValue(problemResponse(409, 'seat-taken', { seatIds: [2] }))
  const err = await api.hold(1, [2]).catch((e: unknown) => e)
  expect(err).toBeInstanceOf(ApiError)
  expect((err as ApiError).status).toBe(409)
  expect((err as ApiError).problem.seatIds).toEqual([2])
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
  expect(url).toBe('/api/bookings/holds')
  const headers = new Headers(init.headers)
  expect(headers.get('Authorization')).toBe('Bearer tok-9')
  expect(headers.has('X-User-Id')).toBe(false)
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body as string)).toEqual({ eventId: 1, seatIds: [2] })
})

it('sends no Authorization on public reads, even when logged in', async () => {
  signIn()
  fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ content: [] }), { status: 200 })))
  await api.catalogEvents()
  await api.event(1)
  await api.catalogEvent(1)
  await api.search({ q: 'x' })
  expect(fetchMock).toHaveBeenCalledTimes(4)
  for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
    expect(new Headers(init.headers).has('Authorization')).toBe(false)
  }
})

it('exposes Retry-After on 429', async () => {
  fetchMock.mockResolvedValue(problemResponse(429, 'rate-limited', {}, { 'Retry-After': '7' }))
  const err = (await api.catalogEvents().catch((e: unknown) => e)) as ApiError
  expect(err.retryAfterSeconds).toBe(7)
})

it('synthesizes a problem when the error body is not JSON', async () => {
  fetchMock.mockResolvedValue(new Response('boom', { status: 502, statusText: 'Bad Gateway' }))
  const err = (await api.catalogEvents().catch((e: unknown) => e)) as ApiError
  expect(err).toBeInstanceOf(ApiError)
  expect(err.problem).toEqual({ type: 'about:blank', title: 'Bad Gateway', status: 502 })
})

it('latestReport returns null on 404', async () => {
  signIn(['ADMIN'])
  fetchMock.mockResolvedValue(new Response(null, { status: 404 }))
  await expect(api.latestReport()).resolves.toBeNull()
})

it('sends the Idempotency-Key on checkout', async () => {
  signIn()
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ orderId: 5 }), { status: 201 }))
  await api.checkout('h1', 'key-1')
  const init = fetchMock.mock.calls[0][1] as RequestInit
  expect(new Headers(init.headers).get('Idempotency-Key')).toBe('key-1')
  expect(JSON.parse(init.body as string)).toEqual({ holdId: 'h1' })
})

it('resolves undefined on 204', async () => {
  signIn(['ADMIN'])
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  await expect(api.reset(1, 30)).resolves.toBeUndefined()
  expect(fetchMock.mock.calls[0][0]).toBe('/api/bookings/admin/events/1/reset?opensInSeconds=30')
})

it('a 401 on an authenticated call clears the session and tells the handler', async () => {
  signIn()
  const handler = vi.fn()
  setUnauthorizedHandler(handler)
  fetchMock.mockResolvedValue(problemResponse(401, 'unauthorized'))
  const err = (await api.hold(1, [2]).catch((e: unknown) => e)) as ApiError
  expect(err.status).toBe(401)
  expect(getSession()).toBeNull()
  expect(handler).toHaveBeenCalledTimes(1)
})

it('a 401 on login (wrong password) and on public reads does not clear or redirect', async () => {
  signIn()
  const handler = vi.fn()
  setUnauthorizedHandler(handler)
  fetchMock.mockImplementation(() => Promise.resolve(problemResponse(401, 'bad-credentials')))
  await expect(api.login('a@b.co', 'nope')).rejects.toBeInstanceOf(ApiError)
  await expect(api.catalogEvents()).rejects.toBeInstanceOf(ApiError)
  expect(getSession()).not.toBeNull()
  expect(handler).not.toHaveBeenCalled()
})

it('login and signup post the credentials without a bearer', async () => {
  signIn()
  fetchMock.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ token: 't' }), { status: 200 })))
  await api.login('a@b.co', 'pw123456')
  await api.signup('a@b.co', 'pw123456', 'Asha')
  const [loginCall, signupCall] = fetchMock.mock.calls as [string, RequestInit][]
  expect(loginCall[0]).toBe('/api/auth/login')
  expect(JSON.parse(loginCall[1].body as string)).toEqual({ email: 'a@b.co', password: 'pw123456' })
  expect(signupCall[0]).toBe('/api/auth/signup')
  expect(JSON.parse(signupCall[1].body as string)).toEqual({
    email: 'a@b.co',
    password: 'pw123456',
    displayName: 'Asha',
  })
  expect(new Headers(loginCall[1].headers).has('Authorization')).toBe(false)
})

it('search sends only the filters that are set', async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ items: [], total: 0, page: 0, size: 20 }), { status: 200 }))
  await api.search({
    q: 'cold play',
    city: 'Mumbai',
    category: '',
    maxPrice: '2500',
    from: '2026-12-01T00:00:00.000Z',
    page: 1,
  })
  const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://x')
  expect(url.pathname).toBe('/api/catalog/events/search')
  expect(Object.fromEntries(url.searchParams)).toEqual({
    q: 'cold play',
    city: 'Mumbai',
    maxPrice: '2500',
    from: '2026-12-01T00:00:00.000Z',
    page: '1',
  })
})

it('fetchTicket returns the blob, null on 404, and uses the bearer', async () => {
  signIn(['USER'], 60_000, 'tok-t')
  fetchMock.mockResolvedValueOnce(
    new Response('%PDF', { status: 200, headers: { 'Content-Type': 'application/pdf' } }),
  )
  const blob = await api.fetchTicket(7)
  expect(blob).toBeInstanceOf(Blob)
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
  expect(url).toBe('/api/notifications/tickets/7.pdf')
  expect(init.method ?? 'GET').toBe('GET')
  expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-t')
  fetchMock.mockResolvedValueOnce(problemResponse(404, 'ticket-not-found'))
  await expect(api.fetchTicket(7)).resolves.toBeNull()
})

it('chaos and dlq use the admin routes', async () => {
  signIn(['ADMIN'])
  fetchMock.mockResolvedValueOnce(
    new Response(JSON.stringify({ failureRate: 0.1, latencyMs: 5, errorRate: 0 }), { status: 200 }),
  )
  await api.chaos()
  fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
  await api.setChaos({ failureRate: 0.2, latencyMs: 10, errorRate: 0 })
  fetchMock.mockResolvedValueOnce(new Response('[]', { status: 200 }))
  await api.dlq(100)
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ replayed: 3 }), { status: 200 }))
  await expect(api.replayDlq(100)).resolves.toEqual({ replayed: 3 })
  const calls = fetchMock.mock.calls as [string, RequestInit][]
  expect(calls.map(([u, i]) => `${i.method} ${u}`)).toEqual([
    'GET /api/payments/admin/chaos',
    'PUT /api/payments/admin/chaos',
    'GET /api/notifications/admin/dlq?limit=100',
    'POST /api/notifications/admin/dlq/replay?limit=100',
  ])
})
