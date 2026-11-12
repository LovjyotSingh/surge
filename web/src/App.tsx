import { useState } from 'react'
import { BrowserRouter, NavLink, Route, Routes } from 'react-router'
import { getUserId, setUserId } from './demoUser'
import EventPage from './pages/EventPage'
import EventsPage from './pages/EventsPage'
import OpsPage from './pages/OpsPage'

function randomUserId(): string {
  return 'demo-' + Math.random().toString(36).slice(2, 6).padEnd(4, '0')
}

function UserPicker() {
  const [id, setId] = useState(getUserId)
  const update = (next: string) => {
    setId(next)
    if (next.trim()) setUserId(next.trim())
  }
  return (
    <div className="user-picker">
      <input value={id} onChange={(e) => update(e.target.value)} aria-label="Demo user" />
      <button type="button" onClick={() => update(randomUserId())}>
        Random
      </button>
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
