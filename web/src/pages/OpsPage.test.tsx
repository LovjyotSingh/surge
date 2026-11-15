import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import type { CatalogEvent, LoadTestReport } from '../api/types'
import OpsPage from './OpsPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      catalogEvents: vi.fn(),
      integrity: vi.fn(),
      latestReport: vi.fn(),
      reset: vi.fn(),
      chaos: vi.fn(),
      setChaos: vi.fn(),
      dlq: vi.fn(),
      replayDlq: vi.fn(),
    },
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
  const ev: CatalogEvent = {
    id: 1,
    title: 'Coldplay Live',
    artist: 'Coldplay',
    category: 'concert',
    venueName: 'Wankhede',
    city: 'Mumbai',
    zoneId: 'Asia/Kolkata',
    startsAt: '2026-12-05T14:00:00Z',
    saleOpensAt: '2026-11-20T10:00:00Z',
    minPriceInr: 1800,
    maxPriceInr: 9000,
    totalSeats: 500,
    seatsLeft: 0,
    description: '',
  }
  mocked.catalogEvents.mockResolvedValue({ content: [ev], page: 0, size: 100, totalElements: 1, totalPages: 1 })
  mocked.chaos.mockResolvedValue({ failureRate: 0.25, latencyMs: 150, errorRate: 0.05 })
  mocked.setChaos.mockResolvedValue(undefined)
  mocked.dlq.mockResolvedValue([])
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

  it('shows chaos settings as percentages and saves edits', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    const failure = await screen.findByLabelText('Failure rate (%)')
    await waitFor(() => expect(failure).toHaveValue(25))
    expect(screen.getByLabelText('Latency (ms)')).toHaveValue(150)
    expect(screen.getByLabelText('Error rate (%)')).toHaveValue(5)

    await user.clear(failure)
    await user.type(failure, '40')
    await user.clear(screen.getByLabelText('Latency (ms)'))
    await user.type(screen.getByLabelText('Latency (ms)'), '800')
    await user.click(screen.getByRole('button', { name: 'Save chaos settings' }))
    expect(await screen.findByText('Chaos settings saved.')).toBeInTheDocument()
    expect(mocked.setChaos).toHaveBeenCalledWith({ failureRate: 0.4, latencyMs: 800, errorRate: 0.05 })
  })

  it('rejects out-of-range chaos values without calling the API', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    const failure = await screen.findByLabelText('Failure rate (%)')
    await waitFor(() => expect(failure).toHaveValue(25))
    await user.clear(failure)
    await user.type(failure, '150')
    await user.click(screen.getByRole('button', { name: 'Save chaos settings' }))
    expect(await screen.findByText(/Rates are 0 to 100 and latency is 0 to 10000/)).toBeInTheDocument()
    expect(mocked.setChaos).not.toHaveBeenCalled()
  })

  it('shows the dead-letter count and replays', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    mocked.dlq
      .mockResolvedValueOnce([
        { messageId: 'm1', orderId: 11, deaths: 3, reason: 'smtp down' },
        { messageId: 'm2', orderId: 12, deaths: 3, reason: 'smtp down' },
      ])
      .mockResolvedValueOnce([])
    mocked.replayDlq.mockResolvedValue({ replayed: 2 })
    render(<OpsPage />)
    expect(await screen.findByText('Dead letters: 2')).toBeInTheDocument()
    expect(screen.getByText(/order 11/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Replay dead letters' }))
    expect(await screen.findByText('Replayed 2 messages.')).toBeInTheDocument()
    expect(mocked.replayDlq).toHaveBeenCalledWith(100)
    expect(await screen.findByText('Dead letters: 0')).toBeInTheDocument()
  })

  it('shows the problem detail when a replay fails', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    mocked.dlq.mockResolvedValue([{ messageId: 'm1', orderId: 11, deaths: 3, reason: 'x' }])
    mocked.replayDlq.mockRejectedValue(
      new ApiError({
        type: 'https://surge.dev/problems/replay-failed',
        title: 'Replay failed',
        status: 503,
        detail: 'Broker connection refused',
      }),
    )
    render(<OpsPage />)
    await user.click(await screen.findByRole('button', { name: 'Replay dead letters' }))
    expect(await screen.findByText('Broker connection refused')).toBeInTheDocument()
  })

  it('sends untouched chaos fields exactly as loaded and accepts .5 and 1e1', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    mocked.chaos.mockResolvedValue({ failureRate: 0.07, latencyMs: 150, errorRate: 0.123456789 })
    render(<OpsPage />)
    const latency = await screen.findByLabelText('Latency (ms)')
    await waitFor(() => expect(latency).toHaveValue(150))
    await user.clear(latency)
    await user.type(latency, '1e1')
    await user.click(screen.getByRole('button', { name: 'Save chaos settings' }))
    await screen.findByText('Chaos settings saved.')
    expect(mocked.setChaos).toHaveBeenLastCalledWith({ failureRate: 0.07, latencyMs: 10, errorRate: 0.123456789 })

    const failure = screen.getByLabelText('Failure rate (%)')
    await user.clear(failure)
    await user.type(failure, '.5')
    await user.click(screen.getByRole('button', { name: 'Save chaos settings' }))
    await waitFor(() => expect(mocked.setChaos).toHaveBeenCalledTimes(2))
    expect(mocked.setChaos).toHaveBeenLastCalledWith({ failureRate: 0.005, latencyMs: 10, errorRate: 0.123456789 })
  })

  it('flags the chaos inputs invalid and linked to the error', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    render(<OpsPage />)
    const failure = await screen.findByLabelText('Failure rate (%)')
    await waitFor(() => expect(failure).toHaveValue(25))
    await user.clear(failure)
    await user.type(failure, '150')
    await user.click(screen.getByRole('button', { name: 'Save chaos settings' }))
    await screen.findByText(/Rates are 0 to 100/)
    expect(failure).toHaveAttribute('aria-invalid', 'true')
    expect(failure).toHaveAttribute('aria-describedby', 'chaos-error')
  })

  it('reports a refresh failure separately from a successful replay', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    mocked.dlq
      .mockResolvedValueOnce([{ messageId: 'm1', orderId: 11, deaths: 3, reason: null }])
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    mocked.replayDlq.mockResolvedValue({ replayed: 1 })
    render(<OpsPage />)
    expect(await screen.findByText(/no reason recorded/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Replay dead letters' }))
    expect(await screen.findByText('Replayed 1 message.')).toBeInTheDocument()
    expect(screen.getByText('Could not refresh the dead-letter list.')).toBeInTheDocument()
    expect(screen.queryByText('Something went wrong — please try again.')).not.toBeInTheDocument()
  })

  it('still refreshes the list after a failed (maybe partial) replay', async () => {
    const user = userEvent.setup()
    mocked.latestReport.mockResolvedValue(null)
    mocked.dlq
      .mockResolvedValueOnce([
        { messageId: 'm1', orderId: 11, deaths: 3, reason: 'x' },
        { messageId: 'm2', orderId: 12, deaths: 3, reason: 'x' },
      ])
      .mockResolvedValueOnce([{ messageId: 'm2', orderId: 12, deaths: 3, reason: 'x' }])
    mocked.replayDlq.mockRejectedValue(
      new ApiError({ type: 'https://surge.dev/problems/replay-failed', title: 'Replay failed', status: 503, detail: 'Broker down' }),
    )
    render(<OpsPage />)
    await user.click(await screen.findByRole('button', { name: 'Replay dead letters' }))
    expect(await screen.findByText('Broker down')).toBeInTheDocument()
    expect(await screen.findByText('Dead letters: 1')).toBeInTheDocument()
  })
})
