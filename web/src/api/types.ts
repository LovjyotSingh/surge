export type SeatState = 'AVAILABLE' | 'HELD' | 'SOLD'
export type Tier = 'PLATINUM' | 'GOLD' | 'SILVER'

export interface EventSummary {
  id: number
  title: string
  venueName: string
  zoneId: string
  saleOpensAt: string
  totalSeats: number
  soldSeats: number
}

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

export interface HoldResponse {
  holdId: string
  eventId: number
  seatIds: number[]
  expiresAt: string
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
