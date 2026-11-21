import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import useSeatStream from './useSeatStream'

/** A stand-in for the browser's EventSource that the test drives by hand. */
class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  readyState = 0
  closed = false
  private listeners = new Map<string, ((e: Event) => void)[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (e: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  close() {
    this.closed = true
    this.readyState = 2
  }

  emit(type: string, readyState: number, data?: string) {
    this.readyState = readyState
    const event = data === undefined ? new Event(type) : new MessageEvent(type, { data })
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  /** The server answered an error status (429, 503): the browser gives up and never reconnects by itself. */
  refuse() {
    this.emit('error', 2)
  }
}

const latest = () => FakeEventSource.instances[FakeEventSource.instances.length - 1]

describe('useSeatStream', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.useFakeTimers()
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.spyOn(Math, 'random').mockReturnValue(1)
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('passes seat changes on', () => {
    const onChange = vi.fn()
    renderHook(() => useSeatStream(1, onChange))

    latest().emit('seats', 1, JSON.stringify({ eventId: 1, seatIds: [3], state: 'HELD' }))

    expect(latest().url).toBe('/api/bookings/events/1/stream')
    expect(onChange).toHaveBeenCalledWith({ eventId: 1, seatIds: [3], state: 'HELD' })
  })

  it('reopens a stream the server refused (429 or 503) after a pause, not at once', () => {
    renderHook(() => useSeatStream(1, vi.fn()))
    const first = latest()

    first.refuse()

    expect(first.closed).toBe(true)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(4_999)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('waits longer after each refusal, up to a minute', () => {
    renderHook(() => useSeatStream(1, vi.fn()))
    const waits: number[] = []
    for (let i = 0; i < 6; i++) {
      latest().refuse()
      const before = FakeEventSource.instances.length
      let waited = 0
      // at most two minutes of fake time, so a stream that is never reopened fails instead of looping forever
      while (FakeEventSource.instances.length === before && waited < 120_000) {
        vi.advanceTimersByTime(1_000)
        waited += 1_000
      }
      waits.push(waited)
    }
    expect(waits).toEqual([5_000, 10_000, 20_000, 40_000, 60_000, 60_000])
  })

  it('leaves a dropped stream to the browser, which reconnects by itself', () => {
    renderHook(() => useSeatStream(1, vi.fn()))

    latest().emit('error', 0)
    vi.advanceTimersByTime(120_000)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(latest().closed).toBe(false)
  })

  it('asks the page to reload the seats it may have missed whenever the stream opens again', () => {
    const onResync = vi.fn()
    renderHook(() => useSeatStream(1, vi.fn(), onResync))

    latest().emit('open', 1)
    expect(onResync).not.toHaveBeenCalled()

    latest().refuse()
    vi.advanceTimersByTime(5_000)
    latest().emit('open', 1)
    expect(onResync).toHaveBeenCalledTimes(1)

    // after a successful open the next refusal starts again from the shortest pause
    latest().refuse()
    vi.advanceTimersByTime(5_000)
    expect(FakeEventSource.instances).toHaveLength(3)
  })

  it('stops for good when the page goes away', () => {
    const { unmount } = renderHook(() => useSeatStream(1, vi.fn()))
    latest().refuse()

    unmount()
    vi.advanceTimersByTime(120_000)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(latest().closed).toBe(true)
  })
})
