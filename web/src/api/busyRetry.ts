import { ApiError } from './client'

/**
 * P2-R26: the gateway answers 503 `service-busy` (with Retry-After) when a service already has as many calls as it may
 * take. The request was not processed, so sending it again unchanged is safe (same seats, same Idempotency-Key). A few
 * retries cover a sale's opening burst; after that the user gets a message and can try again.
 *
 * Each retry still spends a rate-limit token: the gateway's limiters run before its circuit breaker (see its
 * FallbackController), so a busy attempt has already been counted. That is why there are only a few retries, each at
 * least Retry-After (1 s) apart, well inside the per-user hold and order limits (2/s); more would end in 429.
 */
export const BUSY_RETRIES = 3

/** Random extra wait on top of Retry-After, so the refused buyers do not all come back at the same moment. */
const JITTER_MS = 1000

export function isServiceBusy(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 503 && err.problem.type.endsWith('/service-busy')
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/**
 * Runs `call`; while it fails with `service-busy`, calls `onRetry` (show "Busy, retrying…"), waits Retry-After (1 s
 * when absent) plus 0-1 s of jitter and runs it again, at most {@link BUSY_RETRIES} times. Any other error, and the
 * last busy answer, is thrown as it came.
 */
export async function retryWhileBusy<T>(
  call: () => Promise<T>,
  onRetry: () => void,
  wait: (ms: number) => Promise<void> = sleep,
): Promise<T> {
  for (let retries = 0; ; retries++) {
    try {
      return await call()
    } catch (err) {
      if (!isServiceBusy(err) || retries >= BUSY_RETRIES) throw err
      onRetry()
      await wait((err.retryAfterSeconds ?? 1) * 1000 + Math.floor(Math.random() * JITTER_MS))
    }
  }
}
