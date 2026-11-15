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
  it('signs up, logs in and goes to next', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(201, tokenResponse))
    renderAt('/signup?next=/search')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByTestId('where')).toHaveTextContent('/search')
    expect(getSession()?.token).toBe('jwt-123')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      email: 'asha@example.com',
      password: 'hunter2hunter2',
      displayName: 'Asha',
    })
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

  it('shows the Retry-After wait on 429', async () => {
    const user = userEvent.setup()
    fetchMock.mockResolvedValueOnce(json(429, { type: 't', title: 'x', status: 429 }, { 'Retry-After': '2' }))
    renderAt('/signup')
    await user.type(screen.getByLabelText('Name'), 'Asha')
    await fillAndSubmit(user, 'Create account')
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts — try again in 2s.')
  })
})
