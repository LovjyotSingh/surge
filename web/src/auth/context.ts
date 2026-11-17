import { createContext, useContext } from 'react'
import type { UserView } from '../api/types'
import type { Session } from './session'

export interface Auth {
  session: Session | null
  user: UserView | null
  isAdmin: boolean
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, displayName: string) => Promise<void>
  /** Clears the session at once, then revokes the token at the gateway in the background (3 s timeout). */
  logout: () => Promise<void>
  /** Sends the emailed code; on success the session switches to the new, verified token and the old one is revoked. */
  verifyEmail: (code: string) => Promise<void>
}

export const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
