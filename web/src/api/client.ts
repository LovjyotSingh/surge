import { getUserId } from '../demoUser'
import type {
  EventDetail,
  EventSummary,
  HoldResponse,
  IntegrityReport,
  LoadTestReport,
  OrderResponse,
  Problem,
} from './types'

const BASE = '/api/bookings'

export class ApiError extends Error {
  status: number
  problem: Problem
  retryAfterSeconds?: number

  constructor(problem: Problem, retryAfterSeconds?: number) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.status = problem.status
    this.problem = problem
    this.retryAfterSeconds = retryAfterSeconds
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  const header = res.headers.get('Retry-After')
  const retry = header === null ? NaN : Number(header)
  const retryAfterSeconds = Number.isFinite(retry) ? retry : undefined
  let problem: Problem
  try {
    const body: unknown = await res.json()
    if (body === null || typeof body !== 'object') throw new Error('not an object')
    const raw = body as Record<string, unknown>
    problem = {
      ...raw,
      type: typeof raw.type === 'string' ? raw.type : 'about:blank',
      title: typeof raw.title === 'string' ? raw.title : res.statusText || 'Request failed',
      status: typeof raw.status === 'number' ? raw.status : res.status,
    }
  } catch {
    problem = { type: 'about:blank', title: res.statusText || 'Request failed', status: res.status }
  }
  return new ApiError(problem, retryAfterSeconds)
}

interface RequestOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
}

async function request(path: string, opts: RequestOptions = {}): Promise<Response> {
  const headers = new Headers(opts.headers)
  headers.set('X-User-Id', getUserId())
  headers.set('Accept', 'application/json')
  let body: string | undefined
  if (opts.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(opts.body)
  }
  return fetch(BASE + path, { method: opts.method ?? 'GET', headers, body })
}

async function call<T>(path: string, opts?: RequestOptions): Promise<T> {
  const res = await request(path, opts)
  if (!res.ok) throw await toApiError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const api = {
  events: () => call<EventSummary[]>('/events'),
  event: (id: number) => call<EventDetail>(`/events/${id}`),
  hold: (eventId: number, seatIds: number[]) =>
    call<HoldResponse>('/holds', { method: 'POST', body: { eventId, seatIds } }),
  checkout: (holdId: string, idempotencyKey: string) =>
    call<OrderResponse>('/orders', {
      method: 'POST',
      body: { holdId },
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  integrity: (eventId: number) => call<IntegrityReport>(`/admin/events/${eventId}/integrity`),
  reset: (eventId: number, opensInSeconds: number) =>
    call<void>(`/admin/events/${eventId}/reset?opensInSeconds=${opensInSeconds}`, { method: 'POST' }),
  latestReport: async (): Promise<LoadTestReport | null> => {
    const res = await request('/admin/loadtest-reports/latest')
    if (res.status === 404) return null
    if (!res.ok) throw await toApiError(res)
    return (await res.json()) as LoadTestReport
  },
}
