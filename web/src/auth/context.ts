import { createContext, useContext } from 'react'
import type { UserView } from '../api/types'
import type { Session } from './session'

export interface Auth {
  session: Session | null
  user: UserView | null
  isAdmin: boolean
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, displayName: string) => Promise<void>
  /** Revokes the token at the gateway, then clears the session (always, even when that call fails). */
  logout: () => Promise<void>
}

export const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
