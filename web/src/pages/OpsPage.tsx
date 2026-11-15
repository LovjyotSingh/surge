import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { CatalogEvent, DlqMessage, IntegrityReport, LoadTestReport } from '../api/types'
import { GENERIC_ERROR } from '../format'

type Report = LoadTestReport | null | undefined // undefined = loading

const DLQ_LIMIT = 100 // the most the API returns or replays in one call
const CHAOS_ERROR = 'Rates are 0 to 100 and latency is 0 to 10000 ms.'

/** 0.07 * 100 is 7.000000000000001: show what the admin would type. */
const pct = (fraction: number) => String(Number((fraction * 100).toFixed(2)))

function parseChaos(failurePct: string, latency: string, errorPct: string) {
  const rate = (text: string) => (/^\d+(\.\d+)?$/.test(text.trim()) && Number(text) <= 100 ? Number(text) / 100 : null)
  const failureRate = rate(failurePct)
  const errorRate = rate(errorPct)
  const latencyMs = /^\d+$/.test(latency.trim()) ? Number(latency) : null
  if (failureRate === null || errorRate === null || latencyMs === null || latencyMs > 10_000) return null
  return { failureRate, latencyMs, errorRate }
}

// Both profiles: the loadtest service depends on web, which lives in the core profile.
const LOADTEST_COMMAND =
  'docker compose --profile core --profile loadtest run --rm loadtest --users 10000 --event 1'

/** Same verdict as the load tester; `consistent` is missing in reports stored before it existed. */
function passed(r: LoadTestReport): boolean {
  return r.oversold === 0 && r.consistent !== false && r.buyersSucceeded === r.sold
}

export default function OpsPage() {
  const [events, setEvents] = useState<CatalogEvent[]>([])
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

  const [failurePct, setFailurePct] = useState('')
  const [latency, setLatency] = useState('')
  const [errorPct, setErrorPct] = useState('')
  const [chaosLoaded, setChaosLoaded] = useState(false)
  const [chaosError, setChaosError] = useState<string | null>(null)
  const [chaosMessage, setChaosMessage] = useState<string | null>(null)
  const [chaosSaving, setChaosSaving] = useState(false)

  const [dlq, setDlq] = useState<DlqMessage[] | null>(null)
  const [dlqError, setDlqError] = useState<string | null>(null)
  const [dlqMessage, setDlqMessage] = useState<string | null>(null)
  const [replaying, setReplaying] = useState(false)

  useEffect(() => {
    let cancelled = false
    api
      .chaos()
      .then((c) => {
        if (cancelled) return
        setFailurePct(pct(c.failureRate))
        setLatency(String(c.latencyMs))
        setErrorPct(pct(c.errorRate))
        setChaosLoaded(true)
      })
      .catch(() => {
        if (!cancelled) setChaosError(GENERIC_ERROR)
      })
    api
      .dlq(DLQ_LIMIT)
      .then((list) => {
        if (!cancelled) setDlq(list)
      })
      .catch(() => {
        if (!cancelled) setDlqError(GENERIC_ERROR)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const saveChaos = async () => {
    setChaosMessage(null)
    const chaos = parseChaos(failurePct, latency, errorPct)
    if (!chaos) {
      setChaosError(CHAOS_ERROR)
      return
    }
    setChaosError(null)
    setChaosSaving(true)
    try {
      await api.setChaos(chaos)
      setChaosMessage('Chaos settings saved.')
    } catch {
      setChaosError(GENERIC_ERROR)
    } finally {
      setChaosSaving(false)
    }
  }

  const replay = async () => {
    setDlqMessage(null)
    setDlqError(null)
    setReplaying(true)
    try {
      const { replayed } = await api.replayDlq(DLQ_LIMIT)
      setDlqMessage(`Replayed ${replayed} ${replayed === 1 ? 'message' : 'messages'}.`)
      setDlq(await api.dlq(DLQ_LIMIT))
    } catch (err) {
      const failed = err instanceof ApiError && err.problem.type.endsWith('/replay-failed')
      setDlqError(failed && err.problem.detail ? err.problem.detail : GENERIC_ERROR)
    } finally {
      setReplaying(false)
    }
  }

  useEffect(() => {
    let cancelled = false
    api
      .catalogEvents()
      .then((page) => {
        if (cancelled) return
        setEvents(page.content)
        setEventId((cur) => cur ?? page.content[0]?.id ?? null)
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
            <p>
              Sold seats match order lines:{' '}
              {shownIntegrity.consistent
                ? `yes (${shownIntegrity.distinctSeatsSold}) ✅`
                : `no (${shownIntegrity.soldSeats} sold, ${shownIntegrity.distinctSeatsSold} in orders) ❌`}
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
            No load test run yet — run{' '}
            <code>{LOADTEST_COMMAND}</code>
          </p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">Result</th>
                  <th scope="col">Users</th>
                  <th scope="col">Sold</th>
                  <th scope="col">Buyers with 201</th>
                  <th scope="col">Oversold</th>
                  <th scope="col">Hold p50/p95/p99 (ms)</th>
                  <th scope="col">Checkout p50/p95/p99 (ms)</th>
                  <th scope="col">Duration</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>{passed(report) ? 'PASS ✅' : 'FAIL ❌'}</td>
                  <td>{report.users}</td>
                  <td>{report.sold}</td>
                  <td>{report.buyersSucceeded}</td>
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

      <section className="card" aria-label="Payment chaos">
        <h3>Payment chaos</h3>
        <label className="field">
          Failure rate (%)
          <input
            type="number"
            min={0}
            max={100}
            step="any"
            value={failurePct}
            disabled={!chaosLoaded}
            onChange={(e) => setFailurePct(e.target.value)}
          />
        </label>
        <label className="field">
          Latency (ms)
          <input
            type="number"
            min={0}
            max={10000}
            inputMode="numeric"
            value={latency}
            disabled={!chaosLoaded}
            onChange={(e) => setLatency(e.target.value)}
          />
        </label>
        <label className="field">
          Error rate (%)
          <input
            type="number"
            min={0}
            max={100}
            step="any"
            value={errorPct}
            disabled={!chaosLoaded}
            onChange={(e) => setErrorPct(e.target.value)}
          />
        </label>
        {chaosError && (
          <p role="alert" className="field-error">
            {chaosError}
          </p>
        )}
        <button type="button" className="primary" disabled={!chaosLoaded || chaosSaving} onClick={saveChaos}>
          Save chaos settings
        </button>
        {chaosMessage && (
          <p role="status" className="notice success">
            {chaosMessage}
          </p>
        )}
      </section>

      <section className="card" aria-label="Dead letters">
        <h3>Dead letters</h3>
        {dlq === null ? (
          <p className="muted">{dlqError ? 'No data.' : 'Loading…'}</p>
        ) : (
          <>
            <p>
              Dead letters: {dlq.length}
              {dlq.length >= DLQ_LIMIT ? '+' : ''}
            </p>
            <ul className="plain">
              {dlq.slice(0, 5).map((m) => (
                <li key={m.messageId} className="muted">
                  order {m.orderId ?? 'unknown'} · {m.reason}
                </li>
              ))}
            </ul>
          </>
        )}
        {dlqError && (
          <p role="alert" className="field-error">
            {dlqError}
          </p>
        )}
        <button type="button" className="primary" disabled={replaying || !dlq?.length} onClick={replay}>
          Replay dead letters
        </button>
        {dlqMessage && (
          <p role="status" className="notice success">
            {dlqMessage}
          </p>
        )}
      </section>
    </main>
  )
}
