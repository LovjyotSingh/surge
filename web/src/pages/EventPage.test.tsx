import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import type { CatalogEvent, EventDetail, Problem } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { clearSession } from '../auth/session'
import { signIn } from '../test-utils'
import EventPage from './EventPage'

vi.mock('../hooks/useSeatStream', () => ({ default: vi.fn() }))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { event: vi.fn(), catalogEvent: vi.fn(), hold: vi.fn(), checkout: vi.fn(), fetchTicket: vi.fn() },
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

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + loc.search}</p>
}

function renderPage(path = '/events/1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/events/:id" element={<EventPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

const catalogEvent: CatalogEvent = {
  id: 1,
  title: 'Coldplay: Music of the Spheres',
  artist: 'Coldplay',
  category: 'concert',
  venueName: 'Wankhede Stadium',
  city: 'Mumbai',
  zoneId: 'Asia/Kolkata',
  startsAt: '2026-12-05T14:00:00Z',
  saleOpensAt: '2026-11-01T04:30:00Z',
  minPriceInr: 1800,
  maxPriceInr: 9000,
  totalSeats: 3,
  seatsLeft: 3,
  description: '',
}

// The quote (1800, an early-bird price) differs from the seat's base price (2000) on purpose: the Pay button must
// show the locked quote, not a sum of base prices.
const hold = {
  holdId: 'h-1',
  eventId: 1,
  seatIds: [1],
  expiresAt: new Date(Date.now() + 120_000).toISOString(),
  quotedTotalInr: 1800,
  lines: [{ seatId: 1, priceInr: 1800 }],
}

async function pickSeatAndHold(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /Row A seat 1,/ }))
  await user.click(screen.getByRole('button', { name: 'Hold 1 seats' }))
}

beforeEach(() => {
  vi.resetAllMocks()
  signIn()
  mocked.event.mockResolvedValue(detail(past()))
  mocked.catalogEvent.mockResolvedValue(catalogEvent)
  mocked.fetchTicket.mockResolvedValue(null)
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:t')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearSession()
  sessionStorage.clear()
})

describe('EventPage', () => {
  it('sends an unverified user to verify their email when the hold is refused', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValueOnce(new ApiError(problem('email-unverified', 403)))
    renderPage()
    await pickSeatAndHold(user)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Verify your email before holding seats')
    const link = screen.getByRole('link', { name: 'Verify your email' })
    expect(link).toHaveAttribute('href', '/verify?next=/events/1')
    await user.click(link)
    expect(await screen.findByTestId('where')).toHaveTextContent('/verify?next=/events/1')
  })

  it('explains the per-user hold cap', async () => {
    const user = userEvent.setup()
    mocked.hold.mockRejectedValueOnce(new ApiError(problem('too-many-holds', 409)))
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByRole('alert')).toHaveTextContent('already holding seats for other events')
  })

  it('books seats end to end', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout
      .mockRejectedValueOnce(new ApiError(problem('payment-failed', 402)))
      .mockResolvedValueOnce({
        orderId: 42,
        eventId: 1,
        status: 'CONFIRMED',
        totalInr: 1800,
        lines: [],
        createdAt: '',
      })
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByText('Seats held for you')).toBeInTheDocument()
    expect(mocked.hold).toHaveBeenCalledWith(1, [1])

    const pay = screen.getByRole('button', { name: 'Pay ₹1800' })
    await user.click(pay)
    await screen.findByText('Payment failed — your seats are still held. Try again.')
    await user.click(pay)
    expect(await screen.findByText('Booked! Order #42 — ₹1800')).toBeInTheDocument()

    expect(mocked.checkout).toHaveBeenCalledTimes(2)
    const [first, second] = mocked.checkout.mock.calls
    expect(first[0]).toBe('h-1')
    expect(first[1]).toBeTruthy()
    // A declined payment retires the key: the retry is a new attempt, not a replay of the declined one.
    expect(second[1]).toBeTruthy()
    expect(second[1]).not.toBe(first[1])
    expect(await screen.findByText('Preparing your ticket…')).toBeInTheDocument()
    expect(mocked.fetchTicket).toHaveBeenCalledWith(42)
  })

  it('reuses the key when a checkout is retried after a network error', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce({ orderId: 42, eventId: 1, status: 'CONFIRMED', totalInr: 1800, lines: [], createdAt: '' })
    renderPage()
    await pickSeatAndHold(user)
    const pay = await screen.findByRole('button', { name: 'Pay ₹1800' })
    await user.click(pay)
    await screen.findByText('Something went wrong — please try again.')
    await user.click(pay)
    await screen.findByText('Booked! Order #42 — ₹1800')
    const [first, second] = mocked.checkout.mock.calls
    expect(second[1]).toBe(first[1]) // the first attempt may have succeeded: replay, do not double-charge
  })

  it('mints a new key after the server says the old one was reused', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout
      .mockRejectedValueOnce(new ApiError(problem('idempotency-key-reused', 409)))
      .mockResolvedValueOnce({ orderId: 42, eventId: 1, status: 'CONFIRMED', totalInr: 1800, lines: [], createdAt: '' })
    renderPage()
    await pickSeatAndHold(user)
    const pay = await screen.findByRole('button', { name: 'Pay ₹1800' })
    await user.click(pay)
    await screen.findByRole('alert')
    await user.click(pay)
    await screen.findByText('Booked! Order #42 — ₹1800')
    const [first, second] = mocked.checkout.mock.calls
    expect(second[1]).not.toBe(first[1])
  })

  it('clears the booked notice and ticket link when the user logs out', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout.mockResolvedValue({ orderId: 42, eventId: 1, status: 'CONFIRMED', totalInr: 1800, lines: [], createdAt: '' })
    renderPage()
    await pickSeatAndHold(user)
    await user.click(await screen.findByRole('button', { name: 'Pay ₹1800' }))
    await screen.findByText('Booked! Order #42 — ₹1800')
    act(() => clearSession())
    await waitFor(() => expect(screen.queryByText(/Booked!/)).not.toBeInTheDocument())
    expect(screen.queryByText('Preparing your ticket…')).not.toBeInTheDocument()
  })

  it('shows title and venue from the catalog', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Coldplay: Music of the Spheres' })).toBeInTheDocument()
    expect(screen.getByText(/Wankhede Stadium · Mumbai/)).toBeInTheDocument()
  })

  it('falls back to the booking title when the catalog is unavailable', async () => {
    mocked.catalogEvent.mockRejectedValue(new TypeError('Failed to fetch'))
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Coldplay Live' })).toBeInTheDocument()
  })

  it('sends a logged-out visitor to login instead of holding', async () => {
    clearSession()
    const user = userEvent.setup()
    renderPage()
    await pickSeatAndHold(user)
    expect(await screen.findByTestId('where')).toHaveTextContent('/login?next=/events/1')
    expect(mocked.hold).not.toHaveBeenCalled()
  })

  it('keeps hold after payment failure', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout.mockRejectedValue(new ApiError(problem('payment-failed', 402)))
    renderPage()
    await pickSeatAndHold(user)
    await user.click(await screen.findByRole('button', { name: /^Pay ₹1800$/ }))
    expect(
      await screen.findByText('Payment failed — your seats are still held. Try again.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Pay ₹1800$/ })).toBeEnabled()
    expect(screen.getByText('Seats held for you')).toBeInTheDocument()
  })

  it('drops the hold and reloads the map when checkout loses the seats', async () => {
    const user = userEvent.setup()
    mocked.hold.mockResolvedValue(hold)
    mocked.checkout.mockRejectedValue(new ApiError(problem('seat-taken', 409)))
    renderPage()
    await pickSeatAndHold(user)
    await user.click(await screen.findByRole('button', { name: 'Pay ₹1800' }))

    expect(await screen.findByText('Sorry, someone else got seat(s) first.')).toBeInTheDocument()
    await waitFor(() => expect(mocked.event).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: /^Pay/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Seats held for you')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hold 0 seats' })).toBeDisabled()
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
    renderPage('/events/abc')
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
