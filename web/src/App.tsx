import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { BrowserRouter, Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router'
import { setUnauthorizedHandler } from './api/client'
import { AuthProvider } from './auth/AuthContext'
import { useAuth } from './auth/context'
import { loginPath } from './auth/redirect'
import EventPage from './pages/EventPage'
import EventsPage from './pages/EventsPage'
import LoginPage from './pages/LoginPage'
import OpsPage from './pages/OpsPage'
import SearchPage from './pages/SearchPage'
import SignupPage from './pages/SignupPage'
import TicketPage from './pages/TicketPage'
import VerifyPage from './pages/VerifyPage'

/** A 401 on an authenticated call means the session is over: the client clears it, this sends the user to log in again. */
function SessionGuard() {
  const navigate = useNavigate()
  const location = useLocation()
  const here = useRef(location)
  useEffect(() => {
    here.current = location
  })
  useEffect(() => {
    setUnauthorizedHandler(() => {
      const { pathname, search } = here.current
      if (pathname === '/login' || pathname === '/signup') return
      navigate(loginPath(pathname + search), { replace: true })
    })
    return () => setUnauthorizedHandler(null)
  }, [navigate])
  return null
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const { user, isAdmin } = useAuth()
  if (!user) return <Navigate to={loginPath('/ops')} replace />
  if (!isAdmin) {
    return (
      <main>
        <p className="muted">Admins only.</p>
      </main>
    )
  }
  return children
}

function UserMenu() {
  const { user, logout } = useAuth()
  if (!user) {
    return (
      <div className="user-menu">
        <Link to="/login">Log in</Link>
        <Link to="/signup">Sign up</Link>
      </div>
    )
  }
  return (
    <div className="user-menu">
      <span className="user-name" title={user.email}>
        {user.displayName}
      </span>
      <button type="button" onClick={() => void logout()}>
        Log out
      </button>
    </div>
  )
}

function Header() {
  const { isAdmin } = useAuth()
  return (
    <header className="shell-header">
      <h1 className="brand">Surge</h1>
      <nav aria-label="Main">
        <NavLink to="/" end>
          Events
        </NavLink>
        <NavLink to="/search">Search</NavLink>
        {isAdmin && <NavLink to="/ops">Ops</NavLink>}
      </nav>
      <UserMenu />
    </header>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <SessionGuard />
        <div className="shell">
          <Header />
          <Routes>
            <Route path="/" element={<EventsPage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/events/:id" element={<EventPage />} />
            <Route path="/tickets/:orderId" element={<TicketPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/verify" element={<VerifyPage />} />
            <Route
              path="/ops"
              element={
                <RequireAdmin>
                  <OpsPage />
                </RequireAdmin>
              }
            />
          </Routes>
        </div>
      </AuthProvider>
    </BrowserRouter>
  )
}
