import { useEffect, useRef } from 'react'
import type { SeatStateChange } from '../api/types'

export default function useSeatStream(eventId: number, onChange: (c: SeatStateChange) => void) {
  const onChangeRef = useRef(onChange)
  useEffect(() => {
    onChangeRef.current = onChange
  })

  useEffect(() => {
    const source = new EventSource(`/api/bookings/events/${eventId}/stream`)
    const handle = (e: MessageEvent<string>) => {
      try {
        onChangeRef.current(JSON.parse(e.data) as SeatStateChange)
      } catch {
        // ignore malformed frames
      }
    }
    source.addEventListener('seats', handle as EventListener)
    return () => source.close()
  }, [eventId])
}
