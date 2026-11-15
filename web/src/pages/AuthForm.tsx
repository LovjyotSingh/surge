import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/context'
import { safeNext } from '../auth/redirect'
import { GENERIC_ERROR } from '../format'

export type AuthMode = 'login' | 'signup'

function authErrorText(err: unknown, mode: AuthMode): string {
  if (err instanceof ApiError) {
    if (err.status === 429) {
      return err.retryAfterSeconds === undefined
        ? 'Too many attempts — try again shortly.'
        : `Too many attempts — try again in ${err.retryAfterSeconds}s.`
    }
    if (mode === 'login' && err.status === 401) return 'Wrong email or password.'
    if (mode === 'signup' && err.status === 409) return 'That email is already registered.'
  }
  return GENERIC_ERROR
}

/** One form for both pages: signup adds a name and a password-length check. */
export default function AuthForm({ mode }: { mode: AuthMode }) {
  const { user, login, signup } = useAuth()
  const [params] = useSearchParams()
  const rawNext = params.get('next')
  const next = safeNext(rawNext)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Logged in already (or just now): the form has done its job.
  if (user) return <Navigate to={next} replace />

  const otherPath = mode === 'login' ? '/signup' : '/login'
  const otherHref = rawNext ? `${otherPath}?next=${encodeURIComponent(next).replace(/%2F/g, '/')}` : otherPath

  const validate = (): string | null => {
    if (!email.includes('@')) return 'Enter a valid email address.'
    if (mode === 'signup') {
      if (!name.trim()) return 'Enter your name.'
      if (password.length < 8) return 'Use at least 8 characters for the password.'
      if (new TextEncoder().encode(password).length > 72) return 'Use at most 72 bytes for the password.'
    } else if (!password) {
      return 'Enter your password.'
    }
    return null
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    const problem = validate()
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(email.trim(), password)
      else await signup(email.trim(), password, name.trim())
    } catch (err) {
      setError(authErrorText(err, mode))
      setBusy(false)
    }
  }

  return (
    <main>
      <h2>{mode === 'login' ? 'Log in' : 'Sign up'}</h2>
      <form onSubmit={submit} noValidate className="auth-form">
        {error && (
          <p role="alert" className="notice error">
            {error}
          </p>
        )}
        {mode === 'signup' && (
          <label className="field">
            Name
            <input value={name} maxLength={100} autoComplete="name" onChange={(e) => setName(e.target.value)} />
          </label>
        )}
        <label className="field">
          Email
          <input
            type="email"
            value={email}
            maxLength={254}
            autoComplete="email"
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            value={password}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <button type="submit" className="primary" disabled={busy}>
          {mode === 'login' ? 'Log in' : 'Create account'}
        </button>
      </form>
      <p className="muted">
        {mode === 'login' ? 'New here? ' : 'Already have an account? '}
        <Link to={otherHref}>{mode === 'login' ? 'Sign up' : 'Log in'}</Link>
      </p>
    </main>
  )
}
