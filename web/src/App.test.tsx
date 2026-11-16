import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { clearSession, getSession } from './auth/session'
import { signIn } from './test-utils'

const fetchMock = vi.fn()

const emptyPage = { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockImplementation(() =>
    Promise.resolve(new Response(JSON.stringify(emptyPage), { status: 200 })),
  )
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal(
    'EventSource',
    class {
      addEventListener() {}
      close() {}
    },
  )
  window.history.pushState({}, '', '/')
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
  sessionStorage.clear()
})

describe('App header', () => {
  it('shows Login and no Ops when logged out, and no demo-user picker', () => {
    render(<App />)
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(nav).toHaveTextContent('Events')
    expect(nav).toHaveTextContent('Search')
    expect(screen.queryByRole('link', { name: 'Ops' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Demo user')).not.toBeInTheDocument()
  })

  it('hides Ops for a USER and shows the user menu', async () => {
    signIn(['USER'])
    const user = userEvent.setup()
    render(<App />)
    expect(screen.queryByRole('link', { name: 'Ops' })).not.toBeInTheDocument()
    expect(screen.getByText('Asha')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Log out' }))
    await waitFor(() => expect(getSession()).toBeNull())
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
  })
})

describe('logout', () => {
  function logoutCalls() {
    return fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/logout')
  }

  it('revokes the token at the gateway, then clears the session', async () => {
    signIn(['USER'], 3_600_000, 'tok-live')
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url === '/api/auth/logout'
          ? new Response(null, { status: 204 })
          : new Response(JSON.stringify(emptyPage), { status: 200 }),
      ),
    )
    const user = userEvent.setup()
    render(<App />)
    await user.click(screen.getByRole('button', { name: 'Log out' }))
    await waitFor(() => expect(getSession()).toBeNull())
    expect(logoutCalls()).toHaveLength(1)
    const init = logoutCalls()[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-live')
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
  })

  it('still logs out on this device when the call fails', async () => {
    for (const failure of [
      () => Promise.reject(new TypeError('Failed to fetch')),
      () => Promise.resolve(new Response(JSON.stringify({ title: 'x', status: 503 }), { status: 503 })),
      () => Promise.resolve(new Response(JSON.stringify({ title: 'x', status: 401 }), { status: 401 })),
    ]) {
      signIn(['USER'])
      window.history.pushState({}, '', '/')
      fetchMock.mockImplementation((url: string) =>
        url === '/api/auth/logout' ? failure() : Promise.resolve(new Response(JSON.stringify(emptyPage))),
      )
      const user = userEvent.setup()
      const { unmount } = render(<App />)
      await user.click(screen.getByRole('button', { name: 'Log out' }))
      await waitFor(() => expect(getSession()).toBeNull())
      // a failed (even 401) logout never bounces the user to the login page
      expect(window.location.pathname).toBe('/')
      unmount()
    }
  })
})

describe('App header for admins', () => {
  it('shows Ops for an ADMIN', () => {
    signIn(['ADMIN'])
    render(<App />)
    expect(screen.getByRole('link', { name: 'Ops' })).toBeInTheDocument()
  })

  it('keeps a USER out of /ops', () => {
    signIn(['USER'])
    window.history.pushState({}, '', '/ops')
    render(<App />)
    expect(screen.getByText('Admins only.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/admin/'))).toBe(false)
  })

  it('sends a logged-out visitor from /ops to login', async () => {
    window.history.pushState({}, '', '/ops')
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(window.location.pathname + window.location.search).toBe('/login?next=/ops')
  })
})

describe('session expiry', () => {
  it('a 401 from an authenticated call logs out and redirects to login with next', async () => {
    signIn(['USER'])
    window.history.pushState({}, '', '/tickets/42')
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({ type: 'https://surge.dev/problems/unauthorized', title: 'Unauthorized', status: 401 }),
          { status: 401 },
        ),
      ),
    )
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    expect(window.location.search).toBe('?next=/tickets/42')
    expect(getSession()).toBeNull()
  })

  it('an expired stored session counts as logged out', () => {
    signIn(['ADMIN'], -1000)
    render(<App />)
    expect(screen.queryByRole('link', { name: 'Ops' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument()
  })
})
