import { Link } from 'react-router'
import type { CatalogEvent } from '../api/types'
import { formatInr, formatSaleTime, seatsLeftText } from '../format'

export default function EventCard({ event: e }: { event: CatalogEvent }) {
  return (
    <li className="card">
      <h3>
        <Link to={`/events/${e.id}`}>{e.title}</Link>
      </h3>
      {e.artist && <p>{e.artist}</p>}
      <p>
        {e.venueName} · {e.city}
      </p>
      <p className="muted">{formatSaleTime(e.startsAt, e.zoneId)}</p>
      <p className="muted">
        <span>from {formatInr(e.minPriceInr)}</span> · <span>{seatsLeftText(e.seatsLeft)}</span>
      </p>
    </li>
  )
}
