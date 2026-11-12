import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { EventSummary, IntegrityReport, LoadTestReport } from '../api/types'
import { GENERIC_ERROR } from '../format'

type Report = LoadTestReport | null | undefined // undefined = loading

export default function OpsPage() {
  const [events, setEvents] = useState<EventSummary[]>([])
  const [eventId, setEventId] = useState<number | null>(null)
  const [integrity, setIntegrity] = useState<IntegrityReport | null>(null)
  const [report, setReport] = useState<Report>(undefined)
  const [opensIn, setOpensIn] = useState('60')
  const [refresh, setRefresh] = useState(0)
  const [resetting, setResetting] = useState(false)
  const resettingRef = useRef(false)
  const [resetError, setResetError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .events()
      .then((list) => {
        if (cancelled) return
        setEvents(list)
        setEventId((cur) => cur ?? list[0]?.id ?? null)
      })
      .catch(() => {
        if (!cancelled) setError(GENERIC_ERROR)
      })
    api
      .latestReport()
      .then((r) => {
        if (!cancelled) setReport(r)
      })
      .catch(() => {
        if (!cancelled) {
          setReport(null)
          setError(GENERIC_ERROR)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (eventId === null) return
    let cancelled = false
    api
      .integrity(eventId)
      .then((r) => {
        if (!cancelled) setIntegrity(r)
      })
      .catch(() => {
        if (!cancelled) setError(GENERIC_ERROR)
      })
    return () => {
      cancelled = true
    }
  }, [eventId, refresh])

  const shownIntegrity = integrity && integrity.eventId === eventId ? integrity : null

  const reset = async () => {
    if (eventId === null || resettingRef.current) return
    setMessage(null)
    setError(null)
    if (!/^\d+$/.test(opensIn.trim()) || Number(opensIn) > 86400) {
      setResetError('Enter a whole number of seconds from 0 to 86400.')
      return
    }
    setResetError(null)
    resettingRef.current = true
    setResetting(true)
    try {
      await api.reset(eventId, Number(opensIn))
      setMessage('Event reset.')
      setRefresh((n) => n + 1)
    } catch {
      setError(GENERIC_ERROR)
    } finally {
      resettingRef.current = false
      setResetting(false)
    }
  }

  return (
    <main>
      <h2>Ops</h2>
      {error && (
        <p role="alert" className="notice error">
          {error}
        </p>
      )}

      <label className="field">
        Event
        <select value={eventId ?? ''} onChange={(e) => {
            setError(null)
            setEventId(Number(e.target.value))
          }}>
          {events.map((ev) => (
            <option key={ev.id} value={ev.id}>
              {ev.title}
            </option>
          ))}
        </select>
      </label>

      <section className="card" aria-label="Integrity">
        <h3>Integrity</h3>
        {shownIntegrity ? (
          <>
            <p>
              Sold {shownIntegrity.soldSeats} / {shownIntegrity.totalSeats}
            </p>
            <p>
              Oversold: {shownIntegrity.oversold} {shownIntegrity.oversold === 0 ? '✅' : '❌'}
            </p>
          </>
        ) : (
          <p className="muted">{error || eventId === null ? 'No data.' : 'Loading…'}</p>
        )}
      </section>

      <section className="card" aria-label="Latest load test">
        <h3>Latest load test</h3>
        {report === undefined ? (
          <p className="muted">Loading…</p>
        ) : report === null ? (
          <p className="muted">
            No load test run yet — run <code>docker compose --profile loadtest run --rm loadtest</code>
          </p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Users</th>
                  <th scope="col">Sold</th>
                  <th scope="col">Oversold</th>
                  <th scope="col">Hold p50/p95/p99 (ms)</th>
                  <th scope="col">Checkout p50/p95/p99 (ms)</th>
                  <th scope="col">Duration</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>{report.users}</td>
                  <td>{report.sold}</td>
                  <td>{report.oversold}</td>
                  <td>
                    {report.hold.p50} / {report.hold.p95} / {report.hold.p99}
                  </td>
                  <td>
                    {report.checkout.p50} / {report.checkout.p95} / {report.checkout.p99}
                  </td>
                  <td>{(report.durationMs / 1000).toFixed(1)}s</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card" aria-label="Reset event">
        <h3>Reset event</h3>
        <label className="field">
          opens in (seconds)
          <input
            type="number"
            min={0}
            inputMode="numeric"
            value={opensIn}
            onChange={(e) => setOpensIn(e.target.value)}
            aria-invalid={resetError !== null}
          />
        </label>
        {resetError && (
          <p role="alert" className="field-error">
            {resetError}
          </p>
        )}
        <button type="button" className="primary" disabled={eventId === null || resetting} onClick={reset}>
          Reset event
        </button>
        {message && (
          <p role="status" className="notice success">
            {message}
          </p>
        )}
      </section>
    </main>
  )
}
