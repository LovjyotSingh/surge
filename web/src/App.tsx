import { useState } from 'react'
import { BrowserRouter, NavLink, Route, Routes } from 'react-router'
import { getUserId, randomUserId, setUserId } from './demoUser'
import EventPage from './pages/EventPage'
import EventsPage from './pages/EventsPage'
import OpsPage from './pages/OpsPage'

const VALID_ID = /^[A-Za-z0-9_-]{1,64}$/

function UserPicker() {
  const [draft, setDraft] = useState(getUserId)
  const valid = VALID_ID.test(draft)

  const change = (next: string) => {
    setDraft(next)
    if (VALID_ID.test(next)) setUserId(next)
  }

  return (
    <div className="user-picker">
      <input
        value={draft}
        maxLength={64}
        onChange={(e) => change(e.target.value)}
        onBlur={() => {
          if (!valid) setDraft(getUserId())
        }}
        aria-label="Demo user"
        aria-invalid={!valid}
        aria-describedby={valid ? undefined : 'user-hint'}
      />
      <button type="button" onClick={() => change(randomUserId())}>
        Random
      </button>
      {!valid && (
        <span id="user-hint" className="hint">
          Letters, digits, - and _ only
        </span>
      )}
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <div className="shell">
        <header className="shell-header">
          <h1 className="brand">Surge</h1>
          <nav aria-label="Main">
            <NavLink to="/" end>
              Events
            </NavLink>
            <NavLink to="/ops">Ops</NavLink>
          </nav>
          <UserPicker />
        </header>
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventPage />} />
          <Route path="/ops" element={<OpsPage />} />
        </Routes>
      </div>
    </BrowserRouter>
  )
}
