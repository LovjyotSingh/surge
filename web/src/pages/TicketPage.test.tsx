import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { AuthProvider } from '../auth/AuthContext'
import { clearSession } from '../auth/session'
import { signIn } from '../test-utils'
import TicketPage from './TicketPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { fetchTicket: vi.fn() } }
})

function Where() {
  const loc = useLocation()
  return <p data-testid="where">{loc.pathname + loc.search}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/tickets/:orderId" element={<TicketPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.mocked(api.fetchTicket).mockReset()
  vi.mocked(api.fetchTicket).mockResolvedValue(null)
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} }))
})
afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
  sessionStorage.clear()
})

it('redirects a logged-out visitor to login and back', () => {
  renderAt('/tickets/42')
  expect(screen.getByTestId('where')).toHaveTextContent('/login?next=/tickets/42')
  expect(api.fetchTicket).not.toHaveBeenCalled()
})

it('shows the ticket link when logged in', () => {
  signIn()
  renderAt('/tickets/42')
  expect(screen.getByText('Preparing your ticket…')).toBeInTheDocument()
  expect(api.fetchTicket).toHaveBeenCalledWith(42)
})

it('rejects a non-numeric order id', () => {
  signIn()
  renderAt('/tickets/abc')
  expect(screen.getByText('Ticket not found.')).toBeInTheDocument()
  expect(api.fetchTicket).not.toHaveBeenCalled()
})
