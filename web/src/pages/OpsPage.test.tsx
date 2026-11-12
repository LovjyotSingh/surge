import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { LoadTestReport } from '../api/types'
import OpsPage from './OpsPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { events: vi.fn(), integrity: vi.fn(), latestReport: vi.fn(), reset: vi.fn() },
  }
})

const mocked = vi.mocked(api)

const lat = { p50: 11, p95: 22, p99: 33, max: 44 }
const report: LoadTestReport = {
  startedAt: '2026-11-12T10:00:00Z',
  users: 1000,
  eventId: 1,
  totalSeats: 500,
  sold: 500,
  oversold: 0,
  buyersSucceeded: 500,
  requests: 3000,
  conflicts: 10,
  rateLimited: 5,
  errors: 0,
  durationMs: 12_345,
  hold: lat,
  checkout: lat,
}

beforeEach(() => {
  vi.resetAllMocks()
  mocked.events.mockResolvedValue([
    {
      id: 1,
      title: 'Coldplay Live',
      venueName: 'Wankhede',
      zoneId: 'Asia/Kolkata',
      saleOpensAt: '2026-11-20T10:00:00Z',
      totalSeats: 500,
      soldSeats: 500,
    },
  ])
  mocked.integrity.mockResolvedValue({
    eventId: 1,
    totalSeats: 500,
    soldSeats: 500,
    orderItems: 500,
    distinctSeatsSold: 500,
    oversold: 0,
  })
})

describe('OpsPage', () => {
  it('shows report and integrity', async () => {
    mocked.latestReport.mockResolvedValue(report)
    render(<OpsPage />)
    expect(await screen.findByRole('cell', { name: '500' })).toBeInTheDocument()
    expect(await screen.findByText(/✅/)).toBeInTheDocument()
    expect(screen.getByText('1000')).toBeInTheDocument()
  })

  it('shows empty state', async () => {
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    expect(await screen.findByText(/No load test run yet/)).toBeInTheDocument()
  })
})
