import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { clearSession, getSession, setSession } from '../auth/session'
import VerifyPage from './VerifyPage'

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
          <Route path="/verify" element={<VerifyPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

function problem(status: number, slug: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) {
  return json(status, { type: `https://surge.dev/problems/${slug}`, title: slug, status, ...extra }, headers)
}

const unverified = { id: 'u-7', email: 'asha@example.com', displayName: 'Asha', roles: ['USER'], emailVerified: false }

function signInUnverified() {
  setSession({ token: 'tok-unverified', expiresAt: new Date(Date.now() + 1_800_000).toISOString(), user: unverified })
}

async function enter(user: ReturnType<typeof userEvent.setup>, code: string) {
  await user.type(screen.getByLabelText('6-digit code'), code)
  await user.click(screen.getByRole('button', { name: 'Verify' }))
}

beforeEach(() => {
  fetchMock.mockReset()
  // anything a test does not answer itself (the background revoke of the old token): 204
  fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })))
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
  sessionStorage.clear()
})

describe('VerifyPage', () => {
  it('says where the code went, when it expires, and to check Spam including the Gmail app', () => {
    signInUnverified()
    renderAt('/verify')
    expect(screen.getByRole('heading', { name: 'Verify your email' })).toBeInTheDocument()
    expect(screen.getByText('asha@example.com')).toBeInTheDocument()
    expect(screen.getByText(/expires in 15 minutes/)).toBeInTheDocument()
    const hint = screen.getByText(/Check your/)
    expect(hint).toHaveTextContent('Spam')
    expect(hint).toHaveTextContent('Gmail app')
    expect(hint).toHaveTextContent('☰')
  })

  it('verifies the code, swaps in the new token and goes to next', async () => {
    signInUnverified()
    fetchMock.mockResolvedValueOnce(
      json(200, {
        token: 'tok-verified',
        expiresAt: new Date(Date.now() + 1_800_000).toISOString(),
        user: { ...unverified, emailVerified: true },
      }),
    )
    const user = userEvent.setup()
    renderAt('/verify?next=/events/3')
    await enter(user, '123456')

    expect(await screen.findByTestId('where')).toHaveTextContent('/events/3')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/auth/verify')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ code: '123456' })
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-unverified')
    expect(getSession()?.token).toBe('tok-verified')
    expect(getSession()?.user.emailVerified).toBe(true)

    // the old, unverified token is revoked in the background
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([u]) => u === '/api/auth/logout')).toBe(true),
    )
    const [, revoke] = fetchMock.mock.calls.find(([u]) => u === '/api/auth/logout') as [string, RequestInit]
    expect(revoke.method).toBe('POST')
    expect(new Headers(revoke.headers).get('Authorization')).toBe('Bearer tok-unverified')
  })

  it('keeps the verified session when revoking the old token fails', async () => {
    signInUnverified()
    fetchMock.mockImplementation((url: string) =>
      url === '/api/auth/verify'
        ? Promise.resolve(
            json(200, {
              token: 'tok-verified',
              expiresAt: new Date(Date.now() + 1_800_000).toISOString(),
              user: { ...unverified, emailVerified: true },
            }),
          )
        : Promise.reject(new TypeError('Failed to fetch')),
    )
    const user = userEvent.setup()
    renderAt('/verify?next=/events/3')
    await enter(user, '123456')
    expect(await screen.findByTestId('where')).toHaveTextContent('/events/3')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(getSession()?.token).toBe('tok-verified')
  })

  it('keeps only digits and checks for six of them before calling the API', async () => {
    signInUnverified()
    const user = userEvent.setup()
    renderAt('/verify')
    await enter(user, '12a3')
    expect(screen.getByLabelText('6-digit code')).toHaveValue('123')
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter the 6-digit code from the email.')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('explains a wrong, locked or expired code', async () => {
    signInUnverified()
    const user = userEvent.setup()
    renderAt('/verify')
    fetchMock.mockResolvedValueOnce(problem(400, 'verification-code-invalid', { attemptsLeft: 2 }))
    await enter(user, '111111')
    expect(await screen.findByRole('alert')).toHaveTextContent("That code isn't right. 2 attempts left.")

    fetchMock.mockResolvedValueOnce(problem(400, 'verification-code-locked'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Too many wrong codes. Send a new code'))

    fetchMock.mockResolvedValueOnce(problem(400, 'verification-code-expired'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('has expired'))
    expect(getSession()?.token).toBe('tok-unverified')
  })

  describe('Send a new code', () => {
    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
    })
    afterEach(() => {
      vi.useRealTimers()
    })

    it('sends a code, then waits 60 s with a countdown before allowing another', async () => {
      signInUnverified()
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderAt('/verify')
      fetchMock.mockResolvedValueOnce(json(202, { sent: true }))
      await user.click(screen.getByRole('button', { name: 'Send a new code' }))
      expect(await screen.findByRole('status')).toHaveTextContent('We sent a new code to asha@example.com')
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
      expect(url).toBe('/api/auth/verify/resend')
      expect(init.method).toBe('POST')
      expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-unverified')

      // disabled right after sending, counting down
      const button = screen.getByRole('button', { name: /^Resend in \d+s$/ })
      expect(button).toBeDisabled()
      expect(button).toHaveTextContent('Resend in 60s')
      act(() => {
        vi.advanceTimersByTime(18_000)
      })
      expect(button).toHaveTextContent('Resend in 42s')
      expect(button).toBeDisabled()

      // available again once the cooldown is over
      act(() => {
        vi.advanceTimersByTime(42_000)
      })
      expect(screen.getByRole('button', { name: 'Send a new code' })).toBeEnabled()
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    it('waits for the Retry-After of a 429', async () => {
      signInUnverified()
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderAt('/verify')
      fetchMock.mockResolvedValueOnce(problem(429, 'resend-throttled', {}, { 'Retry-After': '42' }))
      await user.click(screen.getByRole('button', { name: 'Send a new code' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('You can ask for a new code in 42s.')
      expect(screen.getByRole('button', { name: 'Resend in 42s' })).toBeDisabled()
      act(() => {
        vi.advanceTimersByTime(42_000)
      })
      expect(screen.getByRole('button', { name: 'Send a new code' })).toBeEnabled()
    })
  })

  it('sends a logged-out visitor to login and back', async () => {
    renderAt('/verify?next=/events/3')
    expect(await screen.findByTestId('where')).toHaveTextContent('/login?next=/verify%3Fnext%3D/events/3')
  })

  it('has nothing to do for a verified user', () => {
    setSession({
      token: 't',
      expiresAt: new Date(Date.now() + 1_800_000).toISOString(),
      user: { ...unverified, emailVerified: true },
    })
    renderAt('/verify?next=/search')
    expect(screen.getByText(/Your email is verified/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue' })).toHaveAttribute('href', '/search')
  })
})
