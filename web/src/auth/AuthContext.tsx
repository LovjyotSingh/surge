import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import { clearSession, getSession, hasRole, setSession, subscribeSession } from './session'
import { AuthContext } from './context'
import type { Auth } from './context'

// setTimeout stores its delay in a signed 32-bit int; a longer delay fires immediately.
const MAX_TIMER = 2_147_483_647

export function AuthProvider({ children }: { children: ReactNode }) {
  const session = useSyncExternalStore(subscribeSession, getSession, getSession)

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.login(email, password)
    setSession({ token: res.token, expiresAt: res.expiresAt, user: res.user })
  }, [])

  const signup = useCallback(async (email: string, password: string, displayName: string) => {
    const res = await api.signup(email, password, displayName)
    setSession({ token: res.token, expiresAt: res.expiresAt, user: res.user })
  }, [])

  // A verified email comes with a new token (ev: true); the old one stays unverified, so it is replaced.
  const verifyEmail = useCallback(async (code: string) => {
    const res = await api.verifyEmail(code)
    setSession({ token: res.token, expiresAt: res.expiresAt, user: res.user })
  }, [])

  // Revoke the token at the gateway, then forget it. The session is cleared even when the call fails (offline,
  // already expired): logging out must always work on this device.
  const logout = useCallback(async () => {
    const token = getSession()?.token
    try {
      if (token) await api.logout(token)
    } catch {
      // nothing more to do: the token still expires on its own
    } finally {
      clearSession()
    }
  }, [])

  // Log out when the token expires while the tab is open, not on the next failed request.
  const expiresAt = session?.expiresAt
  useEffect(() => {
    if (!expiresAt) return
    const wait = Math.min(Math.max(0, Date.parse(expiresAt) - Date.now()), MAX_TIMER)
    const timer = setTimeout(clearSession, wait)
    return () => clearTimeout(timer)
  }, [expiresAt])

  const value = useMemo<Auth>(
    () => ({
      session,
      user: session?.user ?? null,
      isAdmin: hasRole(session, 'ADMIN'),
      login,
      signup,
      logout,
      verifyEmail,
    }),
    [session, login, signup, logout, verifyEmail],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
