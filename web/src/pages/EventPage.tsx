import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, api } from '../api/client'
import type { EventDetail, HoldResponse, OrderResponse, SeatStateChange } from '../api/types'
import Countdown from '../components/Countdown'
import SeatMap from '../components/SeatMap'
import { GENERIC_ERROR, formatSaleTime } from '../format'
import useSeatStream from '../hooks/useSeatStream'

type Notice = { kind: 'error' | 'info'; text: string }

function slugOf(err: ApiError): string {
  return err.problem.type.split('/').pop() ?? ''
}

function describe(err: unknown): Notice {
  if (err instanceof ApiError) {
    const slug = slugOf(err)
    if (err.status === 402) {
      return { kind: 'error', text: 'Payment failed — your seats are still held. Try again.' }
    }
    if (err.status === 429) {
      return { kind: 'error', text: `Slow down — try again in ${err.retryAfterSeconds ?? 1}s.` }
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
  const eventId = Number(raw)
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(eventId)) {
    return (
      <main>
        <p className="muted">Event not found.</p>
      </main>
    )
  }
  return <EventView key={eventId} eventId={eventId} />
}

function EventView({ eventId }: { eventId: number }) {
  const [event, setEvent] = useState<EventDetail | null>(null)
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
      api
        .event(eventId)
        .then((e) => {
          setEvent(e)
          setSaleOpen(new Date(e.saleOpensAt).getTime() <= Date.now())
          setLoadFailed(false)
        })
        .catch(() => setLoadFailed(true)),
    [eventId],
  )

  useEffect(() => {
    let cancelled = false
    api
      .event(eventId)
      .then((e) => {
        if (cancelled) return
        setEvent(e)
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
  useSeatStream(eventId, onStream)

  const toggle = (id: number) => {
    if (hold || order) return
    setSelected((prev) => {
      const next = new Set(prev)
      if (!next.delete(id)) next.add(id)
      return next
    })
  }

  const doHold = async () => {
    setBusy(true)
    setNotice(null)
    try {
      const res = await api.hold(eventId, [...selected])
      idempotencyKey.current = crypto.randomUUID()
      setHold(res)
      setSelected(new Set(res.seatIds))
    } catch (err) {
      setNotice(describe(err))
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
      const res = await api.checkout(hold.holdId, idempotencyKey.current)
      setOrder(res)
      setHold(null)
      setSelected(new Set())
    } catch (err) {
      setNotice(describe(err))
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
      <h2>{event.title}</h2>
      <p className="muted">
        {event.venueName} · Sale opens {formatSaleTime(event.saleOpensAt, event.zoneId)}
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
          </p>
        )}

        {order ? (
          <p role="status" className="notice success">
            Booked! Order #{order.orderId} — ₹{order.totalInr}
          </p>
        ) : hold ? (
          <>
            <p>
              Seats held for you <Countdown target={hold.expiresAt} onDone={onHoldExpired} />
            </p>
            <button type="button" className="primary" disabled={busy} onClick={doPay}>
              Pay ₹{hold.quotedTotalInr}
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
