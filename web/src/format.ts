export function formatSaleTime(iso: string, zoneId: string): string {
  const date = new Date(iso)
  try {
    const when = new Intl.DateTimeFormat('en-IN', {
      timeZone: zoneId,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(date)
    const zone =
      new Intl.DateTimeFormat('en-IN', { timeZone: zoneId, timeZoneName: 'short' })
        .formatToParts(date)
        .find((p) => p.type === 'timeZoneName')?.value ?? zoneId
    return `${when} ${zone}`
  } catch {
    return `${date.toISOString()} (${zoneId})`
  }
}

export const GENERIC_ERROR = 'Something went wrong — please try again.'

/** Rupee amount as the UI shows it everywhere: no grouping, no trailing zeros. */
export function formatInr(amount: number): string {
  return `₹${amount}`
}

export function seatsLeftText(n: number): string {
  if (n <= 0) return 'Sold out'
  return n === 1 ? '1 seat left' : `${n} seats left`
}

/**
 * A wait as people say it: "42s", "4m 10s", "23h 59m". Zero parts are dropped ("1m", "2h"), and from an hour up the
 * seconds are left out. A daily email cap can make a Retry-After almost 24 hours long.
 */
export function formatWait(seconds: number): string {
  const total = Math.max(0, Math.ceil(seconds))
  const join = (big: number, bigUnit: string, small: number, smallUnit: string) =>
    small === 0 ? `${big}${bigUnit}` : `${big}${bigUnit} ${small}${smallUnit}`
  if (total < 60) return `${total}s`
  if (total < 3600) return join(Math.floor(total / 60), 'm', total % 60, 's')
  return join(Math.floor(total / 3600), 'h', Math.floor((total % 3600) / 60), 'm')
}
