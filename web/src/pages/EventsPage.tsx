import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { CatalogPage } from '../api/types'
import EventCard from '../components/EventCard'
import { GENERIC_ERROR } from '../format'

export default function EventsPage() {
  const [page, setPage] = useState<CatalogPage | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    api
      .catalogEvents()
      .then((p) => {
        if (!cancelled) setPage(p)
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <main>
      <h2>Events</h2>
      {error && (
        <p role="alert" className="notice error">
          {GENERIC_ERROR}
        </p>
      )}
      {!error && page === null && <p className="muted">Loading…</p>}
      {page?.content.length === 0 && <p className="muted">No events yet.</p>}
      <ul className="cards">
        {page?.content.map((e) => (
          <EventCard key={e.id} event={e} />
        ))}
      </ul>
      {page && page.totalElements > page.content.length && (
        <p className="muted">
          Showing {page.content.length} of {page.totalElements} — <Link to="/search">search</Link> for the rest.
        </p>
      )}
    </main>
  )
}
