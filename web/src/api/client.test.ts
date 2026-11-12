import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError, api } from './client'
import { getUserId } from '../demoUser'

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
})

it('sends X-User-Id and parses problems', async () => {
  fetchMock.mockResolvedValue(
    new Response(
      JSON.stringify({ type: 'https://surge/problems/seat-taken', title: 'Seat taken', status: 409, seatIds: [2] }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
  const err = await api.hold(1, [2]).catch((e: unknown) => e)
  expect(err).toBeInstanceOf(ApiError)
  expect((err as ApiError).status).toBe(409)
  expect((err as ApiError).problem.seatIds).toEqual([2])
  const init = fetchMock.mock.calls[0][1] as RequestInit
  expect(new Headers(init.headers).get('X-User-Id')).toBe(getUserId())
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body as string)).toEqual({ eventId: 1, seatIds: [2] })
})

it('exposes Retry-After on 429', async () => {
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify({ type: 't', title: 'Slow down', status: 429 }), {
      status: 429,
      headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '7' },
    }),
  )
  const err = (await api.events().catch((e: unknown) => e)) as ApiError
  expect(err.retryAfterSeconds).toBe(7)
})

it('synthesizes a problem when the error body is not JSON', async () => {
  fetchMock.mockResolvedValue(new Response('boom', { status: 502, statusText: 'Bad Gateway' }))
  const err = (await api.events().catch((e: unknown) => e)) as ApiError
  expect(err).toBeInstanceOf(ApiError)
  expect(err.problem).toEqual({ type: 'about:blank', title: 'Bad Gateway', status: 502 })
})

it('latestReport returns null on 404', async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 404 }))
  await expect(api.latestReport()).resolves.toBeNull()
})

it('sends the Idempotency-Key on checkout', async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ orderId: 5 }), { status: 201 }))
  await api.checkout('h1', 'key-1')
  const init = fetchMock.mock.calls[0][1] as RequestInit
  expect(new Headers(init.headers).get('Idempotency-Key')).toBe('key-1')
  expect(JSON.parse(init.body as string)).toEqual({ holdId: 'h1' })
})

it('resolves undefined on 204', async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  await expect(api.reset(1, 30)).resolves.toBeUndefined()
  expect(fetchMock.mock.calls[0][0]).toBe('/api/bookings/admin/events/1/reset?opensInSeconds=30')
})
