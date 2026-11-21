import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { retryWhileBusy } from '../api/busyRetry'
import { ApiError, api } from '../api/client'
import type { CatalogEvent, EventDetail, HoldResponse, OrderResponse, SeatStateChange } from '../api/types'
import { useAuth } from '../auth/context'
import { loginPath, verifyPath } from '../auth/redirect'
import Countdown from '../components/Countdown'
import SeatMap from '../components/SeatMap'
import TicketLink from '../components/TicketLink'
import { GENERIC_ERROR, formatInr, formatSaleTime } from '../format'
import useSeatStream from '../hooks/useSeatStream'

type Notice = { kind: 'error' | 'info'; text: string; link?: { to: string; label: string } }

function slugOf(err: ApiError): string {
  return err.problem.type.split('/').pop() ?? ''
}

function describe(err: unknown, eventId?: number): Notice {
  if (err instanceof ApiError) {
    const slug = slugOf(err)
    if (slug === 'email-unverified') {
      return {
        kind: 'error',
        text: 'Verify your email before holding seats: enter the 6-digit code we emailed you.',
        link: { to: verifyPath(eventId === undefined ? '/' : `/events/${eventId}`), label: 'Verify your email' },
      }
    }
    if (slug === 'too-many-holds') {
      return {
        kind: 'error',
        text: 'You are already holding seats for other events. Buy them or let a hold expire, then try again.',
      }
    }
    if (err.status === 402) {
      return { kind: 'error', text: 'Payment failed — your seats are still held. Try again.' }
    }
    if (err.status === 429) {
      return { kind: 'error', text: `Slow down — try again in ${err.retryAfterSeconds ?? 1}s.` }
    }
    if (slug === 'service-busy' || slug === 'auth-unavailable') {
      // Only after retryWhileBusy has already retried a few times.
      return { kind: 'error', text: 'Surge is very busy right now — try again in a moment.' }
    }
    if (slug === 'seat-taken') {
      return { kind: 'error', text: 'Sorry, someone else got seat(s) first.' }
    }
    if (slug === 'sale-not-open') {
      return { kind: 'error', text: "Sales haven't opened yet." }
    }
    if (slug === 'hold-exists') {
      return { kind: 'error', text: 'You already hold seats for this event.' }
    }
    if (slug === 'hold-not-found') {
      return { kind: 'error', text: 'Your hold expired. Pick seats again.' }
    }
  }
  return { kind: 'error', text: GENERIC_ERROR }
}

export default function EventPage() {
  const raw = useParams().id ?? ''
  const { user } = useAuth()
  const eventId = Number(raw)
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(eventId)) {
    return (
      <main>
        <p className="muted">Event not found.</p>
      </main>
    )
  }
  // Keyed on the user too: logging out (or in as someone else) drops holds, orders and the ticket link.
  return <EventView key={`${eventId}:${user?.id ?? ''}`} eventId={eventId} />
}

/** The seat map is the booking service's; title and venue come from the catalog (the map's own copy is the fallback). */
function fetchEvent(eventId: number): Promise<[EventDetail, CatalogEvent | null]> {
  return Promise.all([api.event(eventId), api.catalogEvent(eventId).catch(() => null)])
}

function EventView({ eventId }: { eventId: number }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [event, setEvent] = useState<EventDetail | null>(null)
  const [catalog, setCatalog] = useState<CatalogEvent | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [saleOpen, setSaleOpen] = useState(false)
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set())
  const [hold, setHold] = useState<HoldResponse | null>(null)
  const [order, setOrder] = useState<OrderResponse | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  const idempotencyKey = useRef<string | null>(null)

  const load = useCallback(
    () =>
      fetchEvent(eventId)
        .then(([e, c]) => {
          setEvent(e)
          setCatalog((prev) => c ?? prev)
          setSaleOpen(new Date(e.saleOpensAt).getTime() <= Date.now())
          setLoadFailed(false)
        })
        .catch(() => setLoadFailed(true)),
    [eventId],
  )

  useEffect(() => {
    let cancelled = false
    fetchEvent(eventId)
      .then(([e, c]) => {
        if (cancelled) return
        setEvent(e)
        setCatalog(c)
        setSaleOpen(new Date(e.saleOpensAt).getTime() <= Date.now())
        setLoadFailed(false)
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [eventId])

  const onStream = useCallback(
    (change: SeatStateChange) => {
      if (change.eventId !== eventId) return
      const ids = new Set(change.seatIds)
      setEvent((prev) =>
        prev
          ? {
              ...prev,
              seats: prev.seats.map((s) => (ids.has(s.id) ? { ...s, state: change.state } : s)),
            }
          : prev,
      )
      if (change.state !== 'AVAILABLE') {
        const own = new Set(hold?.seatIds ?? [])
        setSelected((prev) => {
          const next = new Set([...prev].filter((id) => !ids.has(id) || own.has(id)))
          return next.size === prev.size ? prev : next
        })
      }
    },
    [eventId, hold],
  )
  // A stream that opens again after a failure reloads the seat states it may have missed.
  useSeatStream(eventId, onStream, load)

  const toggle = (id: number) => {
    if (hold || order) return
    setSelected((prev) => {
      const next = new Set(prev)
      if (!next.delete(id)) next.add(id)
      return next
    })
  }

  // A busy service (503 service-busy) is retried by retryWhileBusy; meanwhile the user sees why nothing happened yet.
  const showBusy = () => setNotice({ kind: 'info', text: 'Busy, retrying…' })

  const doHold = async () => {
    if (!user) {
      navigate(loginPath(`/events/${eventId}`))
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      const seatIds = [...selected]
      const res = await retryWhileBusy(() => api.hold(eventId, seatIds), showBusy)
      setNotice(null)
      idempotencyKey.current = crypto.randomUUID()
      setHold(res)
      setSelected(new Set(res.seatIds))
    } catch (err) {
      setNotice(describe(err, eventId))
      if (err instanceof ApiError) {
        const slug = slugOf(err)
        if (slug === 'seat-taken') setSelected(new Set())
        if (slug === 'seat-taken' || slug === 'sale-not-open') await load()
      }
    } finally {
      setBusy(false)
    }
  }

  const doPay = async () => {
    if (!hold || !idempotencyKey.current) return
    setBusy(true)
    setNotice(null)
    try {
      const holdId = hold.holdId
      const key = idempotencyKey.current
      const res = await retryWhileBusy(() => api.checkout(holdId, key), showBusy)
      setNotice(null)
      setOrder(res)
      setHold(null)
      setSelected(new Set())
    } catch (err) {
      setNotice(describe(err))
      // A declined payment (or a key the server says was already used) is a finished attempt: the next try needs a new key.
      if (err instanceof ApiError && (err.status === 402 || slugOf(err) === 'idempotency-key-reused')) {
        idempotencyKey.current = crypto.randomUUID()
      }
      // The hold is gone: it expired, or another order got the seats (the server released the hold).
      if (err instanceof ApiError && ['hold-not-found', 'seat-taken'].includes(slugOf(err))) {
        setHold(null)
        setSelected(new Set())
        await load()
      }
    } finally {
      setBusy(false)
    }
  }

  const onHoldExpired = () => {
    setHold(null)
    setSelected(new Set())
    setNotice({ kind: 'info', text: 'Your hold expired. Pick seats again.' })
    void load()
  }

  if (loadFailed && !event) {
    return (
      <main>
        <p role="alert" className="notice error">
          {GENERIC_ERROR}
        </p>
      </main>
    )
  }
  if (!event) {
    return (
      <main>
        <p className="muted">Loading…</p>
      </main>
    )
  }

  return (
    <main>
      <h2>{catalog?.title ?? event.title}</h2>
      <p className="muted">
        {catalog ? `${catalog.venueName} · ${catalog.city}` : event.venueName} · Sale opens {formatSaleTime(event.saleOpensAt, event.zoneId)}
      </p>

      {!saleOpen && (
        <p className="banner">
          Sale opens in <Countdown target={event.saleOpensAt} onDone={() => setSaleOpen(true)} />
        </p>
      )}

      <SeatMap seats={event.seats} selected={selected} onToggle={toggle} />

      <section className="actions" aria-label="Booking">
        {notice && (
          <p role={notice.kind === 'error' ? 'alert' : 'status'} className={`notice ${notice.kind}`}>
            {notice.text}
            {notice.link && (
              <>
                {' '}
                <Link to={notice.link.to}>{notice.link.label}</Link>
              </>
            )}
          </p>
        )}

        {order ? (
          <>
            <p role="status" className="notice success">
              Booked! Order #{order.orderId} — {formatInr(order.totalInr)}
            </p>
            <TicketLink orderId={order.orderId} />
          </>
        ) : hold ? (
          <>
            <p>
              Seats held for you <Countdown target={hold.expiresAt} onDone={onHoldExpired} />
            </p>
            <button type="button" className="primary" disabled={busy} onClick={doPay}>
              Pay {formatInr(hold.quotedTotalInr)}
            </button>
          </>
        ) : (
          <button
            type="button"
            className="primary"
            disabled={!saleOpen || selected.size === 0 || busy}
            onClick={doHold}
          >
            Hold {selected.size} seats
          </button>
        )}
      </section>
    </main>
  )
}
