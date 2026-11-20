package dev.surge.notification.messaging;

import dev.surge.common.messaging.TicketRequested;
import dev.surge.notification.booking.BookingClient;
import dev.surge.notification.booking.BookingOrder;
import dev.surge.notification.catalog.CatalogClient;
import dev.surge.notification.catalog.CatalogEvent;
import dev.surge.notification.mail.TicketMailer;
import dev.surge.notification.ticket.ProcessedMessageRepository;
import dev.surge.notification.ticket.Ticket;
import dev.surge.notification.ticket.TicketData;
import dev.surge.notification.ticket.TicketPdfRenderer;
import dev.surge.notification.ticket.TicketRepository;
import dev.surge.notification.ticket.TicketStore;
import dev.surge.notification.user.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one {@code TicketRequested} into a stored PDF, a {@code ticket} row and an email, once.
 *
 * <p>Everything runs in one database transaction, in an order chosen so that a retry never sends a second email:
 * claim the message, gather the data (any failure rolls the claim back), render and store the PDF (an idempotent
 * overwrite in S3), upsert the row, send the email, mark the row emailed, commit. A failed email therefore rolls
 * everything back and the retry starts clean.
 *
 * <p>The one gap: if the commit itself fails after the email was sent, the retry sends the email again. This is
 * accepted (at-least-once email, same PDF, same link). The alternative, sending after commit, would instead lose
 * the email when the send fails after the claim committed, which is worse for a ticket.
 */
@Service
public class TicketWorkflow {

    static final String CONSUMER = "tickets";
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ProcessedMessageRepository processed;
    private final TicketRepository tickets;
    private final BookingClient booking;
    private final CatalogClient catalog;
    private final UserDirectory users;
    private final TicketPdfRenderer renderer;
    private final TicketStore store;
    private final TicketMailer mailer;
    private final Clock clock;

    public TicketWorkflow(ProcessedMessageRepository processed, TicketRepository tickets, BookingClient booking,
                          CatalogClient catalog, UserDirectory users, TicketPdfRenderer renderer, TicketStore store,
                          TicketMailer mailer, Clock clock) {
        this.processed = processed;
        this.tickets = tickets;
        this.booking = booking;
        this.catalog = catalog;
        this.users = users;
        this.renderer = renderer;
        this.store = store;
        this.mailer = mailer;
        this.clock = clock;
    }

    /**
     * @return true when the ticket was produced, false when this message id was already handled
     * @throws IllegalArgumentException for a message that can never be processed (it is dead-lettered, not retried)
     */
    @Transactional
    public boolean process(TicketRequested message) {
        validate(message);
        if (processed.claim(message.messageId().toString(), CONSUMER, Instant.now(clock)) == 0) {
            return false;
        }
        BookingOrder order = booking.order(message.userId(), message.orderId());
        CatalogEvent event = catalog.event(message.eventId());
        String email = users.email(message.userId());

        TicketData data = new TicketData(message.orderId(), message.userId(), message.eventId(), event.title(),
                event.venueName(), event.startsAtLocal(), order.seatLabels(), order.totalInr());
        byte[] pdf = renderer.render(data);
        String key = store.put(message.orderId(), pdf);

        Ticket ticket = tickets.saveAndFlush(new Ticket(message.orderId(), message.userId(), message.eventId(), key,
                Instant.now(clock)));
        mailer.send(email, data, pdf);
        ticket.markEmailed(Instant.now(clock));
        return true;
    }

    static void validate(TicketRequested m) {
        if (m == null) {
            throw new IllegalArgumentException("empty message");
        }
        if (m.messageId() == null) {
            throw new IllegalArgumentException("messageId is missing");
        }
        if (m.userId() == null || !VALID_ID.matcher(m.userId()).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        if (m.eventId() <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        if (m.orderId() <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (m.seatIds() == null || m.seatIds().isEmpty()) {
            throw new IllegalArgumentException("seatIds is empty");
        }
    }
}
