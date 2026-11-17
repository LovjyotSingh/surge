import { clearSession, getSession } from '../auth/session'
import type {
  CatalogEvent,
  CatalogPage,
  Chaos,
  DlqMessage,
  EventDetail,
  HoldResponse,
  IntegrityReport,
  LoadTestReport,
  OrderResponse,
  Problem,
  SearchPage,
  SearchParams,
  TokenResponse,
  UserView,
} from './types'

const BOOKINGS = '/api/bookings'
const CATALOG = '/api/catalog'
const AUTH = '/api/auth'

let onUnauthorized: (() => void) | null = null

/** The app registers how to send the user to the login page; the client only decides when. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
}

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
  /**
   * Send the bearer token, and treat a 401 as an expired session (clear it and go to login). Only for calls the
   * gateway authenticates: a public route that receives a stale bearer answers 401 too, so public calls never carry one.
   */
  auth?: boolean
  accept?: string
}

async function request(path: string, opts: RequestOptions = {}): Promise<Response> {
  const headers = new Headers(opts.headers)
  headers.set('Accept', opts.accept ?? 'application/json')
  if (opts.auth) {
    const token = getSession()?.token
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }
  let body: string | undefined
  if (opts.body !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(opts.body)
  }
  const res = await fetch(path, { method: opts.method ?? 'GET', headers, body })
  if (res.status === 401 && opts.auth) {
    clearSession()
    onUnauthorized?.()
  }
  return res
}

function query(params: Record<string, string | number | undefined>): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') qs.set(k, String(v))
  }
  const text = qs.toString()
  return text ? `?${text}` : ''
}

async function call<T>(path: string, opts?: RequestOptions): Promise<T> {
  const res = await request(path, opts)
  if (!res.ok) throw await toApiError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

const ticketUrl = (orderId: number) => `/api/notifications/tickets/${orderId}.pdf`

export const api = {
  login: (email: string, password: string) =>
    call<TokenResponse>(`${AUTH}/login`, { method: 'POST', body: { email, password } }),
  signup: (email: string, password: string, displayName: string) =>
    call<TokenResponse>(`${AUTH}/signup`, { method: 'POST', body: { email, password, displayName } }),
  me: () => call<UserView>(`${AUTH}/me`, { auth: true }),
  /**
   * Revokes `token` at the gateway (204). The caller clears the session whatever happens, so this does not use the
   * `auth` option: a 401 here (already expired or revoked) must not bounce the user to the login page.
   */
  logout: async (token: string, signal?: AbortSignal): Promise<void> => {
    const res = await fetch(`${AUTH}/logout`, {
      method: 'POST',
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
      signal,
    })
    if (!res.ok) throw await toApiError(res)
  },
  /** The emailed 6-digit code; answers a fresh token whose user (and `ev` claim) is verified. */
  verifyEmail: (code: string) =>
    call<TokenResponse>(`${AUTH}/verify`, { method: 'POST', body: { code }, auth: true }),
  /** Emails a new code (202); 429 with Retry-After when asked again too soon. */
  resendVerification: () => call<{ sent: boolean }>(`${AUTH}/verify/resend`, { method: 'POST', auth: true }),

  catalogEvents: (page = 0, size = 100) => call<CatalogPage>(`${CATALOG}/events${query({ page, size })}`),
  catalogEvent: (id: number) => call<CatalogEvent>(`${CATALOG}/events/${id}`),
  search: (params: SearchParams) => call<SearchPage>(`${CATALOG}/events/search${query({ ...params })}`),

  /** Public: the seat map of an event. */
  event: (id: number) => call<EventDetail>(`${BOOKINGS}/events/${id}`),
  hold: (eventId: number, seatIds: number[]) =>
    call<HoldResponse>(`${BOOKINGS}/holds`, { method: 'POST', body: { eventId, seatIds }, auth: true }),
  checkout: (holdId: string, idempotencyKey: string) =>
    call<OrderResponse>(`${BOOKINGS}/orders`, {
      method: 'POST',
      body: { holdId },
      headers: { 'Idempotency-Key': idempotencyKey },
      auth: true,
    }),

  ticketUrl,
  /** The ticket PDF, or null while it does not exist yet (404). A plain link cannot carry the bearer, so this is a fetch. */
  fetchTicket: async (orderId: number): Promise<Blob | null> => {
    const res = await request(ticketUrl(orderId), { auth: true, accept: 'application/pdf' })
    if (res.status === 404) return null
    if (!res.ok) throw await toApiError(res)
    return res.blob()
  },

  integrity: (eventId: number) =>
    call<IntegrityReport>(`${BOOKINGS}/admin/events/${eventId}/integrity`, { auth: true }),
  reset: (eventId: number, opensInSeconds: number) =>
    call<void>(`${BOOKINGS}/admin/events/${eventId}/reset?opensInSeconds=${opensInSeconds}`, {
      method: 'POST',
      auth: true,
    }),
  latestReport: async (): Promise<LoadTestReport | null> => {
    const res = await request(`${BOOKINGS}/admin/loadtest-reports/latest`, { auth: true })
    if (res.status === 404) return null
    if (!res.ok) throw await toApiError(res)
    return (await res.json()) as LoadTestReport
  },

  chaos: () => call<Chaos>('/api/payments/admin/chaos', { auth: true }),
  setChaos: (chaos: Chaos) => call<void>('/api/payments/admin/chaos', { method: 'PUT', body: chaos, auth: true }),
  dlq: (limit: number) => call<DlqMessage[]>(`/api/notifications/admin/dlq?limit=${limit}`, { auth: true }),
  replayDlq: (limit: number) =>
    call<{ replayed: number }>(`/api/notifications/admin/dlq/replay?limit=${limit}`, { method: 'POST', auth: true }),
}
