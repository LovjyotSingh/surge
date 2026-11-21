import { afterEach, describe, expect, it, vi } from 'vitest'
import { BUSY_RETRIES, retryWhileBusy } from './busyRetry'
import { ApiError } from './client'

const busy = (retryAfter?: number) =>
  new ApiError({ type: 'https://surge.dev/problems/service-busy', title: 'Service busy', status: 503 }, retryAfter)
const unavailable = () =>
  new ApiError({ type: 'https://surge.dev/problems/service-unavailable', title: 'Service unavailable', status: 503 }, 5)

afterEach(() => {
  vi.restoreAllMocks()
})

describe('retryWhileBusy', () => {
  it('retries a service-busy answer after Retry-After plus up to 1 s of jitter, then returns the result', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const call = vi.fn().mockRejectedValueOnce(busy(2)).mockResolvedValueOnce('held')
    const waits: number[] = []
    const onRetry = vi.fn()

    const result = await retryWhileBusy(call, onRetry, async (ms) => {
      waits.push(ms)
    })

    expect(result).toBe('held')
    expect(call).toHaveBeenCalledTimes(2)
    expect(waits).toEqual([2500])
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('treats 503 auth-unavailable like service-busy: same wait, same limit of retries', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const authDown = () =>
      new ApiError(
        { type: 'https://surge.dev/problems/auth-unavailable', title: 'Authentication unavailable', status: 503 },
        1,
      )
    const waits: number[] = []
    const onRetry = vi.fn()
    const recovers = vi.fn().mockRejectedValueOnce(authDown()).mockResolvedValueOnce('held')
    await expect(
      retryWhileBusy(recovers, onRetry, async (ms) => {
        waits.push(ms)
      }),
    ).resolves.toBe('held')
    expect(waits).toEqual([1000])
    expect(onRetry).toHaveBeenCalledOnce()

    const last = authDown()
    const never = vi.fn().mockRejectedValue(last)
    await expect(retryWhileBusy(never, () => {}, async () => {})).rejects.toBe(last)
    expect(never).toHaveBeenCalledTimes(BUSY_RETRIES + 1)
  })

  it('waits 1 s when there is no Retry-After', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const waits: number[] = []
    await retryWhileBusy(
      vi.fn().mockRejectedValueOnce(busy()).mockResolvedValueOnce('ok'),
      () => {},
      async (ms) => {
        waits.push(ms)
      },
    )
    expect(waits).toEqual([1000])
  })

  it(`gives up after ${BUSY_RETRIES} retries with the last busy answer`, async () => {
    const last = busy(1)
    const call = vi.fn().mockRejectedValue(last)
    await expect(retryWhileBusy(call, () => {}, async () => {})).rejects.toBe(last)
    expect(call).toHaveBeenCalledTimes(BUSY_RETRIES + 1)
  })

  it('does not retry any other error, another 503 included', async () => {
    const down = unavailable()
    const call = vi.fn().mockRejectedValue(down)
    const wait = vi.fn()
    await expect(retryWhileBusy(call, () => {}, wait)).rejects.toBe(down)
    expect(call).toHaveBeenCalledOnce()
    expect(wait).not.toHaveBeenCalled()
  })
})
