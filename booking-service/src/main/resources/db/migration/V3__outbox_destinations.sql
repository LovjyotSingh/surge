-- Phase 2: TicketRequested goes to the surge.tickets exchange with an explicit routing key instead of a bare queue name.
UPDATE outbox
SET destination = 'rabbit:surge.tickets/ticket.requested'
WHERE destination = 'rabbit:tickets'
  AND published_at IS NULL;
