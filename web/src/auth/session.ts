import type { UserView } from '../api/types'

const KEY = 'surge.session'

export interface Session {
  token: string
  expiresAt: string
  user: UserView
}

// sessionStorage can throw (blocked storage, some private modes): fall back to memory so login still works in the tab.
let memory: string | null = null
let cachedRaw: string | null = null
let cachedValue: Session | null = null
const listeners = new Set<() => void>()

function readRaw(): string | null {
  try {
    return sessionStorage.getItem(KEY)
  } catch {
    return memory
  }
}

function writeRaw(raw: string | null): void {
  memory = raw
  try {
    if (raw === null) sessionStorage.removeItem(KEY)
    else sessionStorage.setItem(KEY, raw)
  } catch {
    // memory copy above is all we have
  }
}

function parse(raw: string): Session | null {
  try {
    const v: unknown = JSON.parse(raw)
    if (v === null || typeof v !== 'object') return null
    const s = v as Record<string, unknown>
    const u = s.user as Record<string, unknown> | null
    if (typeof s.token !== 'string' || typeof s.expiresAt !== 'string') return null
    if (u === null || typeof u !== 'object' || typeof u.id !== 'string') return null
    if (!Array.isArray(u.roles) || !u.roles.every((r) => typeof r === 'string')) return null
    return v as Session
  } catch {
    return null
  }
}

/** The current session, or null when logged out, expired or unreadable. Stable between changes (safe for useSyncExternalStore). */
export function getSession(): Session | null {
  const raw = readRaw()
  if (raw === null) {
    cachedRaw = null
    cachedValue = null
    return null
  }
  if (raw !== cachedRaw) {
    cachedRaw = raw
    cachedValue = parse(raw)
  }
  if (cachedValue === null || !(Date.parse(cachedValue.expiresAt) > Date.now())) {
    writeRaw(null)
    cachedRaw = null
    cachedValue = null
    return null
  }
  return cachedValue
}

export function setSession(session: Session): void {
  writeRaw(JSON.stringify(session))
  listeners.forEach((l) => l())
}

export function clearSession(): void {
  writeRaw(null)
  listeners.forEach((l) => l())
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function hasRole(session: Session | null, role: string): boolean {
  return session?.user.roles.includes(role) ?? false
}
