export type SeatState = 'AVAILABLE' | 'HELD' | 'SOLD'
export type Tier = 'PLATINUM' | 'GOLD' | 'SILVER'

export interface SeatView {
  id: number
  rowLabel: string
  number: number
  tier: Tier
  basePriceInr: number
  state: SeatState
}

export interface EventDetail {
  id: number
  title: string
  venueName: string
  zoneId: string
  saleOpensAt: string
  seats: SeatView[]
}

/** One held seat and the price locked for it when the hold was made. */
export interface HoldLine {
  seatId: number
  priceInr: number
}

export interface HoldResponse {
  holdId: string
  eventId: number
  seatIds: number[]
  expiresAt: string
  /** Exactly what checkout will charge: the prices are locked when the hold is made. */
  quotedTotalInr: number
  lines: HoldLine[]
}

export interface OrderLine {
  seatId: number
  rowLabel: string
  number: number
  priceInr: number
}

export interface OrderResponse {
  orderId: number
  eventId: number
  status: string
  totalInr: number
  lines: OrderLine[]
  createdAt: string
}

export interface SeatStateChange {
  eventId: number
  seatIds: number[]
  state: SeatState
}

export interface IntegrityReport {
  eventId: number
  totalSeats: number
  soldSeats: number
  orderItems: number
  distinctSeatsSold: number
  oversold: number
  /** soldSeats === distinctSeatsSold: every SOLD seat has exactly one order line. */
  consistent: boolean
}

export interface LatencyStats {
  p50: number
  p95: number
  p99: number
  max: number
}

export interface LoadTestReport {
  startedAt: string
  users: number
  eventId: number
  totalSeats: number
  sold: number
  oversold: number
  /** Missing in reports stored before this field existed. */
  consistent?: boolean
  buyersSucceeded: number
  requests: number
  conflicts: number
  rateLimited: number
  errors: number
  durationMs: number
  hold: LatencyStats
  checkout: LatencyStats
}

export interface Problem {
  type: string
  title: string
  status: number
  detail?: string
  requestId?: string
  [k: string]: unknown
}

/** The auth service's view of a user; `roles` is a list such as `["USER"]` or `["ADMIN"]`. */
export interface UserView {
  id: string
  email: string
  displayName: string
  roles: string[]
  /** False until the emailed 6-digit code is entered; holds need it. Missing in sessions stored before it existed. */
  emailVerified?: boolean
}

export interface TokenResponse {
  token: string
  expiresAt: string
  user: UserView
}

/** An event as the catalog describes it (list, detail and search share this shape). */
export interface CatalogEvent {
  id: number
  title: string
  artist: string | null
  category: string
  venueName: string
  city: string
  zoneId: string
  startsAt: string
  saleOpensAt: string
  minPriceInr: number
  maxPriceInr: number
  totalSeats: number
  seatsLeft: number
  description: string | null
}

export interface CatalogPage {
  content: CatalogEvent[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface SearchPage {
  items: CatalogEvent[]
  /** Every match, not just this page. */
  total: number
  page: number
  size: number
}

export interface SearchParams {
  q?: string
  city?: string
  category?: string
  /** ISO instant. */
  from?: string
  /** ISO instant. */
  to?: string
  maxPrice?: string
  page?: number
  size?: number
}

/** failureRate and errorRate are fractions from 0 to 1; latencyMs is 0 to 10000. */
export interface Chaos {
  failureRate: number
  latencyMs: number
  errorRate: number
}

export interface DlqMessage {
  messageId: string
  orderId: number | null
  deaths: number
  reason: string | null
}
