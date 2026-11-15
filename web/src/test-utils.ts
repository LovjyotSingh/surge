import type { UserView } from './api/types'
import { setSession } from './auth/session'

export function user(roles: string[] = ['USER']): UserView {
  return { id: 'u-1', email: 'a@b.co', displayName: 'Asha', roles }
}

/** Puts a valid (or, with a negative `expiresInMs`, expired) session in storage, as a login would. */
export function signIn(roles: string[] = ['USER'], expiresInMs = 3_600_000, token = 'tok-1') {
  setSession({ token, expiresAt: new Date(Date.now() + expiresInMs).toISOString(), user: user(roles) })
}
