import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/context'
import { safeNext } from '../auth/redirect'
import { getSession } from '../auth/session'
import { GENERIC_ERROR } from '../format'

export type AuthMode = 'login' | 'signup'
type Field = 'name' | 'email' | 'password'

const CLOCK_ERROR = 'Your device clock seems to be wrong — please fix it and try again.'

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
  const [invalid, setInvalid] = useState<ReadonlySet<Field>>(new Set())
  const [busy, setBusy] = useState(false)

  // Logged in already (or just now): the form has done its job.
  if (user) return <Navigate to={next} replace />

  const otherPath = mode === 'login' ? '/signup' : '/login'
  const otherHref = rawNext ? `${otherPath}?next=${encodeURIComponent(next).replace(/%2F/g, '/')}` : otherPath

  const validate = (): [Field, string] | null => {
    if (!email.includes('@')) return ['email', 'Enter a valid email address.']
    if (mode === 'signup') {
      if (!name.trim()) return ['name', 'Enter your name.']
      if (password.length < 8) return ['password', 'Use at least 8 characters for the password.']
      if (new TextEncoder().encode(password).length > 72) return ['password', 'Use at most 72 bytes for the password.']
    } else if (!password) {
      return ['password', 'Enter your password.']
    }
    return null
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    const problem = validate()
    if (problem) {
      setError(problem[1])
      setInvalid(new Set([problem[0]]))
      return
    }
    setBusy(true)
    setError(null)
    setInvalid(new Set())
    try {
      if (mode === 'login') await login(email.trim(), password)
      else await signup(email.trim(), password, name.trim())
      // A token that is already expired when it arrives means this device's clock is ahead of the server's.
      if (!getSession()) {
        setError(CLOCK_ERROR)
      }
    } catch (err) {
      setError(authErrorText(err, mode))
      setInvalid(new Set<Field>(['email', 'password']))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <h2>{mode === 'login' ? 'Log in' : 'Sign up'}</h2>
      <form onSubmit={submit} noValidate className="auth-form">
        {error && (
          <p id="auth-error" role="alert" className="notice error">
            {error}
          </p>
        )}
        {mode === 'signup' && (
          <label className="field">
            Name
            <input
              value={name}
              maxLength={100}
              autoComplete="name"
              aria-invalid={invalid.has('name')}
            aria-describedby={error ? 'auth-error' : undefined}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
        )}
        <label className="field">
          Email
          <input
            type="email"
            value={email}
            maxLength={254}
            autoComplete="email"
            aria-invalid={invalid.has('email')}
            aria-describedby={error ? 'auth-error' : undefined}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            value={password}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            aria-invalid={invalid.has('password')}
            aria-describedby={error ? 'auth-error' : undefined}
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
