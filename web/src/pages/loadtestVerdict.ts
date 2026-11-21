import type { LoadTestReport } from '../api/types'

/** Most buyers that may end in an error (5xx, timeouts, too many 429s); conflicts are not errors. */
const MAX_ERROR_PERCENT = 1

/**
 * Same verdict as the load tester: the invariants hold, at least one seat was sold, and at most 1 % of the buyers
 * ended in an error. The load tester now writes its verdict as `passed`, which wins; the rules below only judge reports
 * stored before it existed (`consistent` and `soldInRun` are missing in the oldest ones too).
 */
export function passed(r: LoadTestReport): boolean {
  if (typeof r.passed === 'boolean') {
    return r.passed
  }
  const invariants = r.oversold === 0 && r.consistent !== false && r.buyersSucceeded === r.sold
  const soldSomething = r.totalSeats === 0 || r.sold > 0
  const fewErrors = r.errors * 100 <= r.users * MAX_ERROR_PERCENT
  return invariants && soldSomething && fewErrors
}
