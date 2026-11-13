import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    consistent: true,
  })
})

describe('OpsPage', () => {
  it('shows report and integrity', async () => {
    mocked.latestReport.mockResolvedValue(report)
    render(<OpsPage />)
    expect(await screen.findByRole('cell', { name: 'PASS ✅' })).toBeInTheDocument()
    expect(screen.getAllByRole('cell', { name: '500' })).toHaveLength(2) // sold, buyers with 201
    expect(screen.getByText('1000')).toBeInTheDocument()
    expect(await screen.findByText('Oversold: 0 ✅')).toBeInTheDocument()
    expect(screen.getByText('Sold seats match order lines: yes (500) ✅')).toBeInTheDocument()
  })

  it('flags a failed run and inconsistent seats', async () => {
    mocked.latestReport.mockResolvedValue({ ...report, buyersSucceeded: 499 })
    mocked.integrity.mockResolvedValue({
      eventId: 1,
      totalSeats: 500,
      soldSeats: 501,
      orderItems: 500,
      distinctSeatsSold: 500,
      oversold: 0,
      consistent: false,
    })
    render(<OpsPage />)
    expect(await screen.findByRole('cell', { name: 'FAIL ❌' })).toBeInTheDocument()
    expect(
      await screen.findByText('Sold seats match order lines: no (501 sold, 500 in orders) ❌'),
    ).toBeInTheDocument()
  })

  it('shows empty state with a command that works', async () => {
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    expect(await screen.findByText(/No load test run yet/)).toBeInTheDocument()
    expect(
      screen.getByText(
        'docker compose --profile core --profile loadtest run --rm loadtest --users 10000 --event 1',
      ).tagName,
    ).toBe('CODE')
  })

  it('rejects invalid reset input without calling the API', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    await screen.findByText('Oversold: 0 ✅')
    const input = screen.getByLabelText('opens in (seconds)')
    for (const bad of ['', '-5', '1.5', '86401', 'abc']) {
      await user.clear(input)
      if (bad) await user.type(input, bad)
      await user.click(screen.getByRole('button', { name: 'Reset event' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/0 to 86400/)
    }
    expect(mocked.reset).not.toHaveBeenCalled()
  })

  it('resets once even on double click', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    let finish!: () => void
    mocked.reset.mockReturnValue(new Promise<void>((r) => (finish = r)))
    render(<OpsPage />)
    await screen.findByText('Oversold: 0 ✅')
    const input = screen.getByLabelText('opens in (seconds)')
    await user.clear(input)
    await user.type(input, '30')
    await user.dblClick(screen.getByRole('button', { name: 'Reset event' }))
    expect(mocked.reset).toHaveBeenCalledTimes(1)
    expect(mocked.reset).toHaveBeenCalledWith(1, 30)
    finish()
    expect(await screen.findByText('Event reset.')).toBeInTheDocument()
    await waitFor(() => expect(mocked.integrity).toHaveBeenCalledTimes(2))
  })
})
