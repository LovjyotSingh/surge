import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { CatalogEvent } from '../api/types'
import SearchPage from './SearchPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { search: vi.fn() } }
})

const mocked = vi.mocked(api)

function hit(over: Partial<CatalogEvent> = {}): CatalogEvent {
  return {
    id: 7,
    title: 'Coldplay Live',
    artist: 'Coldplay',
    category: 'concert',
    venueName: 'Wankhede',
    city: 'Mumbai',
    zoneId: 'Asia/Kolkata',
    startsAt: '2026-12-05T14:00:00Z',
    saleOpensAt: '2026-11-01T04:30:00Z',
    minPriceInr: 1800,
    maxPriceInr: 9000,
    totalSeats: 500,
    seatsLeft: 123,
    description: '',
    ...over,
  }
}

function page(items: CatalogEvent[], total = items.length) {
  return { items, total, page: 0, size: 20 }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <SearchPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('SearchPage', () => {
  it('debounces typing into one request and renders result cards', async () => {
    const user = userEvent.setup()
    mocked.search.mockResolvedValue(page([hit()]))
    renderPage()
    await user.type(screen.getByRole('searchbox', { name: 'Search events' }), 'cold')

    const link = await screen.findByRole('link', { name: 'Coldplay Live' })
    expect(link).toHaveAttribute('href', '/events/7')
    expect(screen.getByText('Coldplay')).toBeInTheDocument()
    expect(screen.getByText('Wankhede · Mumbai')).toBeInTheDocument()
    expect(screen.getByText(/5 Dec 2026/)).toBeInTheDocument()
    expect(screen.getByText('from ₹1800')).toBeInTheDocument()
    expect(screen.getByText('123 seats left')).toBeInTheDocument()

    expect(mocked.search).toHaveBeenCalledTimes(1)
    expect(mocked.search.mock.calls[0][0]).toMatchObject({ q: 'cold' })
  })

  it('shows the empty state', async () => {
    mocked.search.mockResolvedValue(page([]))
    renderPage()
    expect(
      await screen.findByText('No events match. Try a different spelling or fewer filters.'),
    ).toBeInTheDocument()
  })

  it('sends city, category, max price and date range (as instants)', async () => {
    const user = userEvent.setup()
    mocked.search.mockResolvedValue(page([hit()]))
    renderPage()
    await user.type(screen.getByLabelText('City'), 'Mumbai')
    await user.selectOptions(screen.getByLabelText('Category'), 'concert')
    await user.type(screen.getByLabelText('Max price (₹)'), '2500')
    await user.type(screen.getByLabelText('From'), '2026-12-01')
    await user.type(screen.getByLabelText('To'), '2026-12-31')
    await waitFor(() => {
      const last = mocked.search.mock.calls.at(-1)?.[0]
      expect(last).toMatchObject({ city: 'Mumbai', category: 'concert', maxPrice: '2500' })
      expect(last?.from).toBe(new Date('2026-12-01T00:00:00').toISOString())
      expect(last?.to).toBe(new Date('2026-12-31T23:59:59.999').toISOString())
    })
  })

  it('shows an error, not the empty state, when the search fails', async () => {
    mocked.search.mockRejectedValue(new TypeError('Failed to fetch'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong — please try again.')
    expect(screen.queryByText(/No events match/)).not.toBeInTheDocument()
  })

  it('loads more when there are more matches than shown', async () => {
    const user = userEvent.setup()
    mocked.search
      .mockResolvedValueOnce({ items: [hit()], total: 2, page: 0, size: 1 })
      .mockResolvedValueOnce({ items: [hit({ id: 8, title: 'Second Show' })], total: 2, page: 1, size: 1 })
    renderPage()
    await screen.findByRole('link', { name: 'Coldplay Live' })
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByRole('link', { name: 'Second Show' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Coldplay Live' })).toBeInTheDocument()
    expect(mocked.search.mock.calls[1][0]).toMatchObject({ page: 1 })
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })
})
