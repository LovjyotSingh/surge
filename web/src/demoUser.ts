const KEY = 'surge.userId'

let memoryId: string | undefined

export function randomUserId(): string {
  return 'demo-' + Math.random().toString(36).slice(2, 6).padEnd(4, '0')
}

export function getUserId(): string {
  try {
    const existing = sessionStorage.getItem(KEY)
    if (existing) return existing
    const id = randomUserId()
    sessionStorage.setItem(KEY, id)
    return id
  } catch {
    return memoryId ?? (memoryId = randomUserId())
  }
}

export function setUserId(id: string): void {
  memoryId = id
  try {
    sessionStorage.setItem(KEY, id)
  } catch {
    // sessionStorage unavailable; keep in memory only
  }
}
