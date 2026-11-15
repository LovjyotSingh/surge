import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CatalogEvent, SearchParams } from '../api/types'
import EventCard from '../components/EventCard'
import { GENERIC_ERROR } from '../format'

interface Filters {
  q: string
  city: string
  category: string
  maxPrice: string
  from: string
  to: string
}

const NO_FILTERS: Filters = { q: '', city: '', category: '', maxPrice: '', from: '', to: '' }
const CATEGORIES = ['concert', 'comedy', 'sports', 'theatre']
const CITIES = ['Delhi', 'Dubai', 'Singapore']
const DEBOUNCE_MS = 300

function instant(day: string, time: string): string | undefined {
  const d = new Date(`${day}T${time}`) // local time: the day the user picked, in their zone
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

function toParams(f: Filters, page: number): SearchParams {
  return {
    q: f.q.trim(),
    city: f.city.trim(),
    category: f.category,
    maxPrice: /^\d+(\.\d+)?$/.test(f.maxPrice.trim()) ? f.maxPrice.trim() : undefined,
    from: f.from ? instant(f.from, '00:00:00') : undefined,
    to: f.to ? instant(f.to, '23:59:59.999') : undefined,
    page,
  }
}

interface Results {
  status: 'loading' | 'ok' | 'error'
  items: CatalogEvent[]
  total: number
  page: number
  /** What the shown results were searched with, so "Load more" continues the same search. */
  filters: Filters
}

export default function SearchPage() {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [results, setResults] = useState<Results>({
    status: 'loading',
    items: [],
    total: 0,
    page: 0,
    filters: NO_FILTERS,
  })
  const [loadingMore, setLoadingMore] = useState(false)

  useEffect(() => {
    let cancelled = false
    const timer = setTimeout(() => {
      setResults((r) => ({ ...r, status: 'loading' }))
      api
        .search(toParams(filters, 0))
        .then((res) => {
          if (!cancelled) setResults({ status: 'ok', items: res.items, total: res.total, page: res.page, filters })
        })
        .catch(() => {
          if (!cancelled) setResults((r) => ({ ...r, status: 'error' }))
        })
    }, DEBOUNCE_MS)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [filters])

  const loadMore = async () => {
    setLoadingMore(true)
    try {
      const res = await api.search(toParams(results.filters, results.page + 1))
      setResults((r) => ({ ...r, status: 'ok', items: [...r.items, ...res.items], total: res.total, page: res.page }))
    } catch {
      setResults((r) => ({ ...r, status: 'error' }))
    } finally {
      setLoadingMore(false)
    }
  }

  const set = (key: keyof Filters) => (e: { target: { value: string } }) =>
    setFilters((f) => ({ ...f, [key]: e.target.value }))

  return (
    <main>
      <h2>Search</h2>
      <form className="filters" role="search" onSubmit={(e) => e.preventDefault()}>
        <label className="field wide">
          Search events
          <input type="search" value={filters.q} onChange={set('q')} placeholder="Artist, title or venue" />
        </label>
        <label className="field">
          City
          <input value={filters.city} onChange={set('city')} list="search-cities" />
          <datalist id="search-cities">
            {CITIES.map((c) => (
              <option key={c} value={c} />
            ))}
          </datalist>
        </label>
        <label className="field">
          Category
          <select value={filters.category} onChange={set('category')}>
            <option value="">All</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Max price (₹)
          <input type="number" min={0} inputMode="numeric" value={filters.maxPrice} onChange={set('maxPrice')} />
        </label>
        <label className="field">
          From
          <input type="date" value={filters.from} onChange={set('from')} />
        </label>
        <label className="field">
          To
          <input type="date" value={filters.to} onChange={set('to')} />
        </label>
      </form>

      {results.status === 'error' && (
        <p role="alert" className="notice error">
          {GENERIC_ERROR}
        </p>
      )}
      {results.status === 'loading' && <p className="muted">Searching…</p>}
      {results.status === 'ok' && results.items.length === 0 && (
        <p className="muted">No events match. Try a different spelling or fewer filters.</p>
      )}
      <ul className="cards">
        {results.items.map((e) => (
          <EventCard key={e.id} event={e} />
        ))}
      </ul>
      {results.items.length > 0 && results.items.length < results.total && (
        <button type="button" disabled={loadingMore} onClick={loadMore}>
          Load more
        </button>
      )}
    </main>
  )
}
