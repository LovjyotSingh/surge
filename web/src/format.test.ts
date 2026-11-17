import { describe, expect, it } from 'vitest'
import { formatWait } from './format'

describe('formatWait', () => {
  it.each([
    [0, '0s'],
    [0.2, '1s'],
    [42, '42s'],
    [59, '59s'],
    [60, '1m'],
    [250, '4m 10s'],
    [3599, '59m 59s'],
    [3600, '1h'],
    [3660, '1h 1m'],
    [86_399, '23h 59m'],
    [86_400, '24h'],
  ])('%s seconds is "%s"', (seconds, text) => {
    expect(formatWait(seconds)).toBe(text)
  })
})
