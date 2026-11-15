import { Navigate, useParams } from 'react-router'
import { useAuth } from '../auth/context'
import { loginPath } from '../auth/redirect'
import TicketLink from '../components/TicketLink'

/** Where the ticket e-mail points: `/tickets/<orderId>`. */
export default function TicketPage() {
  const raw = useParams().orderId ?? ''
  const { user } = useAuth()
  const orderId = Number(raw)
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(orderId)) {
    return (
      <main>
        <p className="muted">Ticket not found.</p>
      </main>
    )
  }
  if (!user) return <Navigate to={loginPath(`/tickets/${orderId}`)} replace />
  return (
    <main>
      <h2>Your ticket</h2>
      <p className="muted">Order #{orderId}</p>
      <TicketLink orderId={orderId} />
    </main>
  )
}
