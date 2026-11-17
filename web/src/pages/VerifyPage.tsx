import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { ApiError, api } from '../api/client'
import { useAuth } from '../auth/context'
import { loginPath, safeNext, verifyPath } from '../auth/redirect'
import { GENERIC_ERROR, formatWait } from '../format'

type Notice = { kind: 'error' | 'info'; text: string }

/** The server allows one new code per 60 s (and 5 an hour, 10 a day: those come back as a 429's Retry-After). */
const RESEND_GAP_SECONDS = 60

function slugOf(err: ApiError): string {
  return err.problem.type.split('/').pop() ?? ''
}

function verifyErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    const slug = slugOf(err)
    if (slug === 'verification-code-invalid') {
      const left = err.problem.attemptsLeft
      return typeof left === 'number'
        ? `That code isn't right. ${left} ${left === 1 ? 'attempt' : 'attempts'} left.`
        : "That code isn't right."
    }
    if (slug === 'verification-code-locked') return 'Too many wrong codes. Send a new code and try again.'
    if (slug === 'verification-code-expired') return 'That code has expired or was already used. Send a new code.'
    if (slug === 'already-verified') return 'Your email is already verified. Log out and back in to continue.'
    if (err.status === 400) return 'Enter the 6-digit code from the email.'
    if (err.status === 429) return `Too many attempts — try again in ${formatWait(err.retryAfterSeconds ?? 60)}.`
  }
  return GENERIC_ERROR
}

function resendErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 429) {
      return err.retryAfterSeconds === undefined
        ? 'You asked for a new code very recently — wait a little and try again.'
        : `You can ask for a new code in ${formatWait(err.retryAfterSeconds)}.`
    }
    if (slugOf(err) === 'already-verified') return 'Your email is already verified. Log out and back in to continue.'
    if (slugOf(err) === 'mail-unavailable') return "We couldn't send the email right now. Try again in a minute."
  }
  return GENERIC_ERROR
}

/** Where to check when the email does not arrive; shown on this page and in the email itself. */
export function SpamHint() {
  return (
    <p className="muted spam-hint">
      Can't find it? Check your <strong>Spam</strong> or Junk folder. In the Gmail app, tap <strong>☰</strong> (the
      menu) → <strong>Spam</strong>. If it's there, open it and tap <strong>Not spam</strong> so our next emails reach
      your inbox.
    </p>
  )
}

/** Enter the 6-digit code emailed at signup (or ask for a new one). Verifying swaps in a token with `ev: true`. */
export default function VerifyPage() {
  const { user, verifyEmail } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const [code, setCode] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)
  const [busy, setBusy] = useState(false)
  // "Send a new code" is available again at this time (ms): 60 s after a send, or what a 429's Retry-After says.
  const [resendAt, setResendAt] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const coolingDown = resendAt > now
  useEffect(() => {
    if (!coolingDown) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [coolingDown])
  const secondsLeft = Math.ceil((resendAt - now) / 1000)

  const waitBeforeResend = (seconds: number) => {
    const t = Date.now()
    setNow(t)
    setResendAt(t + seconds * 1000)
  }

  if (!user) {
    return <Navigate to={loginPath(verifyPath(next))} replace />
  }

  if (user.emailVerified) {
    return (
      <main>
        <h2>Email verified</h2>
        <p role="status" className="notice success">
          Your email is verified. You can book seats.
        </p>
        <Link to={next}>Continue</Link>
      </main>
    )
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (busy) return
    if (!/^\d{6}$/.test(code)) {
      setNotice({ kind: 'error', text: 'Enter the 6-digit code from the email.' })
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      await verifyEmail(code)
      navigate(next, { replace: true })
    } catch (err) {
      setNotice({ kind: 'error', text: verifyErrorText(err) })
    } finally {
      setBusy(false)
    }
  }

  const resend = async () => {
    if (busy || coolingDown) return
    setBusy(true)
    setNotice(null)
    try {
      await api.resendVerification()
      setCode('')
      waitBeforeResend(RESEND_GAP_SECONDS)
      setNotice({ kind: 'info', text: `We sent a new code to ${user.email}. It expires in 15 minutes.` })
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        waitBeforeResend(err.retryAfterSeconds ?? RESEND_GAP_SECONDS)
      }
      setNotice({ kind: 'error', text: resendErrorText(err) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <h2>Verify your email</h2>
      <p>
        We sent a 6-digit code to <strong>{user.email}</strong>. It expires in 15 minutes. You need to verify your
        email before you can hold seats.
      </p>
      <SpamHint />
      <form onSubmit={submit} noValidate className="auth-form">
        {notice && (
          <p
            id="verify-notice"
            role={notice.kind === 'error' ? 'alert' : 'status'}
            className={`notice ${notice.kind}`}
          >
            {notice.text}
          </p>
        )}
        <label className="field">
          6-digit code
          <input
            value={code}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            aria-invalid={notice?.kind === 'error'}
            aria-describedby={notice ? 'verify-notice' : undefined}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          />
        </label>
        <button type="submit" className="primary" disabled={busy}>
          Verify
        </button>
        <button type="button" disabled={busy || coolingDown} onClick={() => void resend()}>
          {coolingDown ? `Resend in ${formatWait(secondsLeft)}` : 'Send a new code'}
        </button>
      </form>
      <p className="muted">
        <Link to={next}>Not now</Link>
      </p>
    </main>
  )
}
