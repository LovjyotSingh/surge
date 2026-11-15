import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from '../api/client'
import type { CatalogEvent, Chaos, DlqMessage, IntegrityReport, LoadTestReport } from '../api/types'
import { GENERIC_ERROR } from '../format'

type Report = LoadTestReport | null | undefined // undefined = loading

const DLQ_LIMIT = 100 // the most the API returns or replays in one call
const CHAOS_ERROR = 'Rates are 0 to 100 and latency is 0 to 10000 ms.'

type ChaosField = keyof Chaos

/** Shows a fraction as the percentage an admin would type (0.07 is "7", not 7.000000000000001). Display only. */
const pct = (fraction: number) => String(Number((fraction * 100).toPrecision(12)))

const num = (text: string): number | null => (text.trim() !== '' && Number.isFinite(Number(text)) ? Number(text) : null)

/**
 * Builds the body to PUT. A field the admin did not touch keeps the backend's exact value (no round trip through a
 * percentage); an edited field is converted from what was typed. Null when an edited field is out of range.
 */
function buildChaos(loaded: Chaos, dirty: ReadonlySet<ChaosField>, failurePct: string, latency: string, errorPct: string) {
  const rate = (field: 'failureRate' | 'errorRate', text: string) => {
    if (!dirty.has(field)) return loaded[field]
    const v = num(text)
    return v !== null && v >= 0 && v <= 100 ? v / 100 : null
  }
  const failureRate = rate('failureRate', failurePct)
  const errorRate = rate('errorRate', errorPct)
  let latencyMs: number | null = loaded.latencyMs
  if (dirty.has('latencyMs')) {
    const v = num(latency)
    latencyMs = v !== null && Number.isInteger(v) && v >= 0 && v <= 10_000 ? v : null
  }
  if (failureRate === null || errorRate === null || latencyMs === null) return null
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
  const [loadedChaos, setLoadedChaos] = useState<Chaos | null>(null)
  const [dirty, setDirty] = useState<ReadonlySet<ChaosField>>(new Set())
  const chaosLoaded = loadedChaos !== null
  const touch = (field: ChaosField) => setDirty((d) => new Set(d).add(field))
  const [chaosError, setChaosError] = useState<string | null>(null)
  const [chaosMessage, setChaosMessage] = useState<string | null>(null)
  const [chaosSaving, setChaosSaving] = useState(false)

  const [dlq, setDlq] = useState<DlqMessage[] | null>(null)
  const [dlqError, setDlqError] = useState<string | null>(null)
  const [dlqMessage, setDlqMessage] = useState<string | null>(null)
  const [dlqRefreshError, setDlqRefreshError] = useState<string | null>(null)
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
        setLoadedChaos(c)
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

  // Only a validation problem marks the inputs invalid; a failed load or save is not the fields' fault.
  const chaosInvalid = chaosError === CHAOS_ERROR
  const chaosAria = {
    'aria-invalid': chaosInvalid,
    'aria-describedby': chaosError !== null ? 'chaos-error' : undefined,
  }

  const saveChaos = async () => {
    setChaosMessage(null)
    const chaos = loadedChaos && buildChaos(loadedChaos, dirty, failurePct, latency, errorPct)
    if (!chaos) {
      setChaosError(CHAOS_ERROR)
      return
    }
    setChaosError(null)
    setChaosSaving(true)
    try {
      await api.setChaos(chaos)
      setLoadedChaos(chaos)
      setDirty(new Set())
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
    setDlqRefreshError(null)
    setReplaying(true)
    try {
      const { replayed } = await api.replayDlq(DLQ_LIMIT)
      setDlqMessage(`Replayed ${replayed} ${replayed === 1 ? 'message' : 'messages'}.`)
    } catch (err) {
      const failed = err instanceof ApiError && err.problem.type.endsWith('/replay-failed')
      setDlqError(failed && err.problem.detail ? err.problem.detail : GENERIC_ERROR)
    }
    // Refresh either way: a failed replay may still have moved some messages. Its own message, so a refresh problem
    // is never reported as a replay problem.
    try {
      setDlq(await api.dlq(DLQ_LIMIT))
    } catch {
      setDlqRefreshError('Could not refresh the dead-letter list.')
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
            {...chaosAria}
            onChange={(e) => {
              touch('failureRate')
              setFailurePct(e.target.value)
            }}
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
            {...chaosAria}
            onChange={(e) => {
              touch('latencyMs')
              setLatency(e.target.value)
            }}
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
            {...chaosAria}
            onChange={(e) => {
              touch('errorRate')
              setErrorPct(e.target.value)
            }}
          />
        </label>
        {chaosError && (
          <p id="chaos-error" role="alert" className="field-error">
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
                  order {m.orderId ?? 'unknown'} · {m.reason ?? 'no reason recorded'}
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
        {dlqRefreshError && (
          <p role="alert" className="field-error">
            {dlqRefreshError}
          </p>
        )}
        {dlqMessage && (
          <p role="status" className="notice success">
            {dlqMessage}
          </p>
        )}
      </section>
    </main>
  )
}
