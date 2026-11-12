import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { EventSummary } from '../api/types'
import { GENERIC_ERROR, formatSaleTime } from '../format'

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    api
      .events()
      .then((list) => {
        if (!cancelled) setEvents(list)
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
      {!error && events === null && <p className="muted">Loading…</p>}
      {events?.length === 0 && <p className="muted">No events yet.</p>}
      <ul className="cards">
        {events?.map((e) => (
          <li key={e.id} className="card">
            <h3>
              <Link to={`/events/${e.id}`}>{e.title}</Link>
            </h3>
            <p>{e.venueName}</p>
            <p className="muted">Sale opens {formatSaleTime(e.saleOpensAt, e.zoneId)}</p>
            <p className="muted">
              {e.soldSeats} / {e.totalSeats} sold
            </p>
          </li>
        ))}
      </ul>
    </main>
  )
}
