import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import type { EventDetail, Problem } from '../api/types'
import EventPage from './EventPage'

vi.mock('../hooks/useSeatStream', () => ({ default: vi.fn() }))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { event: vi.fn(), hold: vi.fn(), checkout: vi.fn() },
  }
})

const mocked = vi.mocked(api)

function detail(saleOpensAt: string): EventDetail {
  return {
    id: 1,
    title: 'Coldplay Live',
    venueName: 'Wankhede',
    zoneId: 'Asia/Kolkata',
    saleOpensAt,
    seats: [1, 2, 3].map((n) => ({
      id: n,
      rowLabel: 'A',
      number: n,
      tier: 'GOLD' as const,
      basePriceInr: 2000,
      state: 'AVAILABLE' as const,
    })),
  }
}

const past = () => new Date(Date.now() - 60_000).toISOString()
const future = () => new Date(Date.now() + 3_600_000).toISOString()

function problem(slug: string, status: number): Problem {
  return { type: `https://surge.dev/problems/${slug}`, title: slug, status }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/events/1']}>
      <Routes>
        <Route path="/events/:id" element={<EventPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const hold = {
  holdId: 'h-1',
  eventId: 1,
  seatIds: [1],
  expiresAt: new Date(Date.now() + 120_000).toISOString(),
}

async function pickSeatAndHold(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /Row A seat 1,/ }))
  await user.click(screen.getByRole('button', { name: 'Hold 1 seats' }))
}

beforeEach(() => {
  vi.resetAllMocks()
  mocked.event.mockResolvedValue(detail(past()))
})

describe('EventPage', () => {
  it('books seats end to end', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout
      .mockRejectedValueOnce(new ApiError(problem('payment-failed', 402)))
      .mockResolvedValueOnce({
        orderId: 42,
        eventId: 1,
        status: 'CONFIRMED',
        totalInr: 4999,
        lines: [],
        createdAt: '',
      })
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Seats held for you')).toBeInTheDocument()
    expect(mocked.hold).toHaveBeenCalledWith(1, [1])

    const pay = screen.getByRole('button', { name: 'Pay ₹2000 (final price at checkout)' })
    await user.click(pay)
    await screen.findByText('Payment failed — your seats are still held. Try again.')
    await user.click(pay)
    expect(await screen.findByText('Booked! Order #42 — ₹4999')).toBeInTheDocument()

    expect(mocked.checkout).toHaveBeenCalledTimes(2)
    const [first, second] = mocked.checkout.mock.calls
    expect(first[0]).toBe('h-1')
    expect(first[1]).toBeTruthy()
    expect(second[1]).toBe(first[1])
  })

  it('keeps hold after payment failure', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout.mockRejectedValue(new ApiError(problem('payment-failed', 402)))
    renderPage()
    await pickSeatAndHold(user)
    await user.click(await screen.findByRole('button', { name: /^Pay ₹2000/ }))
    expect(
      await screen.findByText('Payment failed — your seats are still held. Try again.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Pay ₹2000/ })).toBeEnabled()
    expect(screen.getByText('Seats held for you')).toBeInTheDocument()
  })

  it('handles seat taken', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValue(new ApiError(problem('seat-taken', 409)))
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Sorry, someone else got seat(s) first.')).toBeInTheDocument()
    await waitFor(() => expect(mocked.event).toHaveBeenCalledTimes(2))
  })

  it('shows retry-after on 429', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValue(new ApiError(problem('rate-limited', 429), 7))
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Slow down — try again in 7s.')).toBeInTheDocument()
  })

  it('falls back on network errors', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValue(new TypeError('Failed to fetch'))
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Something went wrong — please try again.')).toBeInTheDocument()
  })

  it('shows expiry when the hold is already expired', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue({ ...hold, expiresAt: new Date(Date.now() - 1000).toISOString() })
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Your hold expired. Pick seats again.')).toBeInTheDocument()
    await waitFor(() => expect(mocked.event).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: /^Pay/ })).not.toBeInTheDocument()
  })

  it('handles sale not open', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValue(new ApiError(problem('sale-not-open', 409)))
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText("Sales haven't opened yet.")).toBeInTheDocument()
    await waitFor(() => expect(mocked.event).toHaveBeenCalledTimes(2))
  })

  it('shows not found for a non-numeric id without fetching', () => {
    render(
      <MemoryRouter initialEntries={['/events/abc']}>
        <Routes>
          <Route path="/events/:id" element={<EventPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByText('Event not found.')).toBeInTheDocument()
    expect(mocked.event).not.toHaveBeenCalled()
  })

  it('disables hold before sale opens', async () => {
    mocked.event.mockResolvedValue(detail(future()))
    renderPage()
    expect(await screen.findByText('Sale opens in')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Hold \d+ seats$/ })).toBeDisabled()
  })
})
