import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../api/client'
import TicketLink from './TicketLink'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { fetchTicket: vi.fn() } }
})

const mocked = vi.mocked(api)
const create = vi.fn(() => 'blob:ticket-1')
const revoke = vi.fn()

beforeEach(() => {
  vi.resetAllMocks()
  create.mockReturnValue('blob:ticket-1')
  vi.useFakeTimers()
  vi.spyOn(URL, 'createObjectURL').mockImplementation(create)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revoke)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const tick = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))

describe('TicketLink', () => {
  it('polls every 2s with GET until the ticket exists, then offers the download', async () => {
    mocked.fetchTicket
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(new Blob(['%PDF'], { type: 'application/pdf' }))
    render(<TicketLink orderId={42} />)
    await tick(0)
    expect(screen.getByText('Preparing your ticket…')).toBeInTheDocument()
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(1)
    await tick(1999)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(1)
    await tick(1)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(2)
    await tick(2000)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(3)

    const link = screen.getByRole('link', { name: 'Download ticket (PDF)' })
    expect(link).toHaveAttribute('href', 'blob:ticket-1')
    expect(link).toHaveAttribute('download', 'surge-ticket-42.pdf')
    await tick(10_000)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(3) // stopped polling
  })

  it('gives up after 60s with an email fallback message', async () => {
    mocked.fetchTicket.mockResolvedValue(null)
    render(<TicketLink orderId={42} />)
    await tick(61_000)
    expect(
      screen.getByText('Your ticket is still being prepared — it will also arrive by email.'),
    ).toBeInTheDocument()
    const calls = mocked.fetchTicket.mock.calls.length
    expect(calls).toBeGreaterThanOrEqual(30)
    expect(calls).toBeLessThanOrEqual(32)
    await tick(10_000)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(calls)
  })

  it('keeps polling through transient errors', async () => {
    mocked.fetchTicket
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Blob(['%PDF']))
    render(<TicketLink orderId={5} />)
    await tick(2000)
    expect(screen.getByRole('link', { name: 'Download ticket (PDF)' })).toBeInTheDocument()
  })

  it('stops polling on unmount and revokes the object URL', async () => {
    mocked.fetchTicket.mockResolvedValueOnce(null).mockResolvedValueOnce(new Blob(['%PDF']))
    const { unmount } = render(<TicketLink orderId={9} />)
    await tick(2000)
    expect(create).toHaveBeenCalledTimes(1)
    unmount()
    expect(revoke).toHaveBeenCalledWith('blob:ticket-1')

    mocked.fetchTicket.mockReset()
    mocked.fetchTicket.mockResolvedValue(null)
    const second = render(<TicketLink orderId={10} />)
    await tick(0)
    second.unmount()
    await tick(10_000)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(1)
  })

  it('stops on a session-expiry 401 without claiming the ticket is missing', async () => {
    mocked.fetchTicket.mockRejectedValue(new ApiError({ type: 't', title: 'Unauthorized', status: 401 }))
    render(<TicketLink orderId={9} />)
    await tick(10_000)
    expect(mocked.fetchTicket).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired — log in again to get your ticket.')
  })

  it('announces readiness in the same live region', async () => {
    mocked.fetchTicket.mockResolvedValueOnce(null).mockResolvedValueOnce(new Blob(['%PDF']))
    render(<TicketLink orderId={3} />)
    await tick(0)
    const region = screen.getByRole('status')
    expect(region).toHaveAttribute('aria-live', 'polite')
    expect(region).toHaveTextContent('Preparing your ticket…')
    await tick(2000)
    expect(screen.getByRole('status')).toBe(region)
    expect(region).toHaveTextContent('Your ticket is ready.')
  })

  it('ignores a ticket that arrives after unmount: no object URL, no state update', async () => {
    let finish!: (b: Blob | null) => void
    mocked.fetchTicket.mockReturnValue(new Promise((r) => (finish = r)))
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { unmount } = render(<TicketLink orderId={4} />)
    await tick(0)
    unmount()
    await act(async () => finish(new Blob(['%PDF'])))
    expect(create).not.toHaveBeenCalled()
    expect(revoke).not.toHaveBeenCalled()
    expect(errors).not.toHaveBeenCalled()
  })
})
