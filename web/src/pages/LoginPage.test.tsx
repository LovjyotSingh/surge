import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import { clearSession, getSession } from '../auth/session'
import LoginPage from './LoginPage'
import SignupPage from './SignupPage'

const fetchMock = vi.fn()

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + loc.search}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

const tokenResponse = {
  token: 'jwt-123',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  user: { id: 'u-7', email: 'asha@example.com', displayName: 'Asha', roles: ['USER'] },
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, button: string, password = 'hunter2hunter2') {
  await user.type(screen.getByLabelText('Email'), 'asha@example.com')
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: button }))
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
  sessionStorage.clear()
})

describe('LoginPage', () => {
  it('stores the session, goes to next, and later calls carry the bearer', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(200, tokenResponse))
    renderAt('/login?next=/events/3')
    await fillAndSubmit(user, 'Log in')

    expect(await screen.findByTestId('where')).toHaveTextContent('/events/3')
    expect(getSession()?.token).toBe('jwt-123')
    expect(JSON.parse(sessionStorage.getItem('surge.session') as string).user.id).toBe('u-7')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      email: 'asha@example.com',
      password: 'hunter2hunter2',
    })

    fetchMock.mockResolvedValueOnce(json(200, { holdId: 'h' }))
    await api.hold(3, [1])
    const init = fetchMock.mock.calls[1][1] as RequestInit
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jwt-123')
  })

  it('nudges a user whose email is not verified to the verify page', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(200, { ...tokenResponse, user: { ...tokenResponse.user, emailVerified: false } }))
    renderAt('/login?next=/events/3')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByTestId('where')).toHaveTextContent('/verify?next=/events/3')
  })

  it('goes straight to next when the email is verified', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(200, { ...tokenResponse, user: { ...tokenResponse.user, emailVerified: true } }))
    renderAt('/login?next=/events/3')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByTestId('where')).toHaveTextContent('/events/3')
    expect(screen.getByTestId('where')).not.toHaveTextContent('verify')
  })

  it('defaults to the home page when next is missing or not a local path', async () => {
    for (const next of ['', '?next=https://evil.example', '?next=//evil.example', '?next=javascript:alert(1)', '?next=/%09/evil.example', '?next=%5C%5Cevil']) {
      const user = userEvent.setup()
      fetchMock.mockResolvedValueOnce(json(200, tokenResponse))
      const { unmount } = renderAt('/login' + next)
      await fillAndSubmit(user, 'Log in')
      expect(await screen.findByTestId('where')).toHaveTextContent(/^\/$/)
      clearSession()
      unmount()
    }
  })

  it('says wrong email or password on 401 and stays logged out', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(
      json(401, { type: 'https://surge.dev/problems/bad-credentials', title: 'Bad credentials', status: 401 }),
    )
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.')
    expect(getSession()).toBeNull()
  })

  it('shows the Retry-After wait on 429', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(
      json(
        429,
        { type: 'https://surge.dev/problems/rate-limited', title: 'Too many requests', status: 429 },
        { 'Retry-After': '3' },
      ),
    )
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts — try again in 3s.')
  })

  it('falls back gracefully when 429 has no Retry-After', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(429, { type: 't', title: 'Too many requests', status: 429 }))
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts — try again shortly.')
  })

  it('explains a wrong device clock when the new session is already expired', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(200, { ...tokenResponse, expiresAt: new Date(Date.now() - 1000).toISOString() }))
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByRole('alert')).toHaveTextContent('Your device clock seems to be wrong')
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-describedby', 'auth-error')
  })

  it('marks the fields invalid and re-enables the form after a failure', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(401, { type: 't', title: 'Bad credentials', status: 401 }))
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
  })

  it('falls back on network errors', async () => {
    const user = userEvent.setup()
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    renderAt('/login')
    await fillAndSubmit(user, 'Log in')
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong — please try again.')
  })

  it('links to signup keeping next', () => {
    renderAt('/login?next=/events/3')
    expect(screen.getByRole('link', { name: 'Sign up' })).toHaveAttribute('href', '/signup?next=/events/3')
  })
})

describe('SignupPage', () => {
  it('signs up, logs in and goes to verify the email, then next', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(201, { ...tokenResponse, user: { ...tokenResponse.user, emailVerified: false } }))
    renderAt('/signup?next=/search')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByTestId('where')).toHaveTextContent('/verify?next=/search')
    expect(getSession()?.token).toBe('jwt-123')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      email: 'asha@example.com',
      password: 'hunter2hunter2',
      displayName: 'Asha',
    })
  })

  it('explains why a password was refused, without repeating it', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(
      json(400, {
        type: 'https://surge.dev/problems/validation-failed',
        title: 'Validation failed',
        status: 400,
        errors: { password: 'is one of the most common passwords; choose one that is harder to guess' },
      }),
    )
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account', 'password1')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This password is one of the most common passwords')
    expect(alert).not.toHaveTextContent('password1')
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true')
    expect(getSession()).toBeNull()
  })

  it('says the email is already registered on 409', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(
      json(409, { type: 'https://surge.dev/problems/email-taken', title: 'Email already registered', status: 409 }),
    )
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByRole('alert')).toHaveTextContent('That email is already registered.')
  })

  it('rejects a short password without calling the API', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account', 'short')
    expect(await screen.findByRole('alert')).toHaveTextContent('Use at least 8 characters for the password.')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('accepts a name with initials, an apostrophe, a hyphen and accented letters', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(201, { ...tokenResponse, user: { ...tokenResponse.user, emailVerified: false } }))
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), "J.R. O'Neil-Zoë 2")
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByTestId('where')).toHaveTextContent('/verify')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string).displayName).toBe("J.R. O'Neil-Zoë 2")
  })

  it('refuses a name that looks like a web address without calling the API', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'evil.com')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Use letters, digits, spaces and . ' - only (up to 60), and no web addresses.",
    )
    expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('refuses other symbols in the name', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha <b>')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByRole('alert')).toHaveTextContent('Use letters, digits, spaces')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not let the name grow past 60 characters', async () => {
    const user = userEvent.setup()
    renderAt('/signup')
    const name = screen.getByLabelText('Name')
    expect(name).toHaveAttribute('maxlength', '60')
    await user.type(name, 'a'.repeat(70))
    expect(name).toHaveValue('a'.repeat(60))
  })

  it('shows the Retry-After wait on 429', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(429, { type: 't', title: 'x', status: 429 }, { 'Retry-After': '2' }))
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts — try again in 2s.')
  })
})
