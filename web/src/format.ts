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
