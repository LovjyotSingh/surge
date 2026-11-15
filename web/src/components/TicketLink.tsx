import { useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'

const POLL_MS = 2000
const GIVE_UP_MS = 60_000

type State = { kind: 'waiting' } | { kind: 'ready'; href: string } | { kind: 'late' } | { kind: 'signed-out' }

/**
 * The ticket is made asynchronously after checkout. A link cannot carry the bearer token, so poll with GET (404 = not
 * there yet) and offer the downloaded blob through an object URL.
 */
export default function TicketLink({ orderId }: { orderId: number }) {
  const [state, setState] = useState<State>({ kind: 'waiting' })

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let objectUrl: string | undefined
    const started = Date.now()

    const poll = async () => {
      try {
        const blob = await api.fetchTicket(orderId)
        if (cancelled) return
        if (blob) {
          objectUrl = URL.createObjectURL(blob)
          setState({ kind: 'ready', href: objectUrl })
          return
        }
      } catch (err) {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) {
          setState({ kind: 'signed-out' }) // the client already sent the user to the login page
          return
        }
        // anything else is treated like "not yet": keep trying until the time is up
      }
      if (Date.now() - started >= GIVE_UP_MS) {
        setState({ kind: 'late' })
        return
      }
      timer = setTimeout(poll, POLL_MS)
    }
    void poll()

    return () => {
      cancelled = true
      clearTimeout(timer)
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [orderId])

  const message =
    state.kind === 'ready'
      ? 'Your ticket is ready.'
      : state.kind === 'late'
        ? 'Your ticket is still being prepared — it will also arrive by email.'
        : state.kind === 'signed-out'
          ? 'Your session expired — log in again to get your ticket.'
          : 'Preparing your ticket…'

  // One live region that stays mounted, so the change from "Preparing" to "ready" is announced.
  return (
    <>
      <p role="status" aria-live="polite" className="muted">
        {message}
      </p>
      {state.kind === 'ready' && (
        <p>
          <a className="button-link" href={state.href} download={`surge-ticket-${orderId}.pdf`}>
            Download ticket (PDF)
          </a>
        </p>
      )}
    </>
  )
}
