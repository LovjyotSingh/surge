import type { SeatView, Tier } from '../api/types'

interface Props {
  seats: SeatView[]
  selected: ReadonlySet<number>
  onToggle: (seatId: number) => void
  maxSelect?: number
}

const TIERS: Tier[] = ['PLATINUM', 'GOLD', 'SILVER']

function groupByRow(seats: SeatView[]): [string, SeatView[]][] {
  const rows = new Map<string, SeatView[]>()
  for (const seat of seats) {
    const row = rows.get(seat.rowLabel)
    if (row) row.push(seat)
    else rows.set(seat.rowLabel, [seat])
  }
  return [...rows].map(([label, row]) => [label, [...row].sort((a, b) => a.number - b.number)])
}

export default function SeatMap({ seats, selected, onToggle, maxSelect = 8 }: Props) {
  const full = selected.size >= maxSelect
  return (
    <div className="seatmap">
      <ul className="seat-legend" aria-label="Seat tiers">
        {TIERS.map((t) => (
          <li key={t} className={`legend-item tier-${t.toLowerCase()}`}>
            {t}
          </li>
        ))}
      </ul>
      <div className="seat-scroll">
        <div className="seat-grid" role="group" aria-label="Seats">
          {groupByRow(seats).map(([label, row]) => (
            <div className="seat-row" key={label}>
              <span className="row-label" aria-hidden="true">
                {label}
              </span>
              {row.map((seat) => {
                const isSelected = selected.has(seat.id)
                const available = seat.state === 'AVAILABLE'
                const state = isSelected ? 'selected' : seat.state.toLowerCase()
                return (
                  <button
                    key={seat.id}
                    type="button"
                    className={`seat tier-${seat.tier.toLowerCase()} ${state}`}
                    aria-label={`Row ${seat.rowLabel} seat ${seat.number}, ${seat.tier}, ₹${seat.basePriceInr}, ${seat.state.toLowerCase()}`}
                    aria-pressed={available || isSelected ? isSelected : undefined}
                    disabled={!available && !isSelected}
                    onClick={() => {
                      if (!isSelected && full) return
                      onToggle(seat.id)
                    }}
                  >
                    {seat.number}
                  </button>
                )
              })}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
