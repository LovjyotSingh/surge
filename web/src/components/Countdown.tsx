import { useEffect, useRef, useState } from 'react'

interface Props {
  target: string
  onDone: () => void
}

function remainingSeconds(target: string): number {
  return Math.max(0, Math.ceil((new Date(target).getTime() - Date.now()) / 1000))
}

function format(total: number): string {
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const ss = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${String(m).padStart(2, '0')}:${ss}`
}

export default function Countdown({ target, onDone }: Props) {
  const [left, setLeft] = useState(() => remainingSeconds(target))
  const onDoneRef = useRef(onDone)
  const firedRef = useRef(false)

  useEffect(() => {
    onDoneRef.current = onDone
  })

  useEffect(() => {
    firedRef.current = false
    const tick = () => {
      const next = remainingSeconds(target)
      setLeft(next)
      if (next === 0 && !firedRef.current) {
        firedRef.current = true
        onDoneRef.current()
      }
    }
    tick()
    const id = setInterval(tick, 250)
    return () => clearInterval(id)
  }, [target])

  return (
    <span className="countdown" role="timer">
      {format(left)}
    </span>
  )
}
