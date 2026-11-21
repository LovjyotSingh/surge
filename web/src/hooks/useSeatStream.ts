import { useEffect, useRef } from 'react'
import type { SeatStateChange } from '../api/types'

/** EventSource.CLOSED: the browser has given up on the stream and will not reconnect by itself. */
const CLOSED = 2
const FIRST_RETRY_MS = 5_000
const MAX_RETRY_MS = 60_000

/**
 * The live seat map. When the stream drops, the browser reconnects by itself. When the server refuses it outright
 * with an error status, the browser gives up for good: nginx answers 429 past 4 streams per address, and booking
 * answers 503 stream-capacity when a sale has filled its stream slots. Then this hook opens a new stream after a pause
 * of 5 s, doubling up to a minute, with jitter so a crowd that was refused together does not come back together.
 * Whenever a stream opens again (after either kind of failure) it calls {@code onResync}, so the page can reload the
 * seat states it missed meanwhile.
 */
export default function useSeatStream(
  eventId: number,
  onChange: (c: SeatStateChange) => void,
  onResync?: () => void,
) {
  const onChangeRef = useRef(onChange)
  const onResyncRef = useRef(onResync)
  useEffect(() => {
    onChangeRef.current = onChange
    onResyncRef.current = onResync
  })

  useEffect(() => {
    let source: EventSource | null = null
    let retry: ReturnType<typeof setTimeout> | undefined
    let refusals = 0
    let opened = false
    let stopped = false

    const handle = (e: MessageEvent<string>) => {
      try {
        onChangeRef.current(JSON.parse(e.data) as SeatStateChange)
      } catch {
        // ignore malformed frames
      }
    }

    const open = () => {
      const current = new EventSource(`/api/bookings/events/${eventId}/stream`)
      source = current
      current.addEventListener('seats', handle as EventListener)
      current.addEventListener('open', () => {
        if (opened) onResyncRef.current?.()
        opened = true
        refusals = 0
      })
      current.addEventListener('error', () => {
        if (stopped || current.readyState !== CLOSED) return
        current.close()
        const pause = Math.min(MAX_RETRY_MS, FIRST_RETRY_MS * 2 ** refusals)
        refusals += 1
        retry = setTimeout(open, pause * (0.5 + Math.random() / 2))
      })
    }

    open()
    return () => {
      stopped = true
      clearTimeout(retry)
      source?.close()
    }
  }, [eventId])
}
