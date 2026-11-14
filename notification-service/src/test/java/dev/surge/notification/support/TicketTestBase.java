package dev.surge.notification.support;

import static org.mockito.Mockito.doReturn;

import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.TicketRequested;
import dev.surge.notification.booking.BookingClient;
import dev.surge.notification.booking.BookingOrder;
import dev.surge.notification.catalog.CatalogClient;
import dev.surge.notification.catalog.CatalogEvent;
import dev.surge.notification.mail.TicketMailer;
import dev.surge.notification.messaging.TicketWorkflow;
import dev.surge.notification.ticket.TicketRepository;
import dev.surge.notification.ticket.TicketStore;
import dev.surge.notification.user.UserDirectory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers: stubbed remote services, raw publishing like booking's outbox does, queue inspection. */
public abstract class TicketTestBase {

    private static final AtomicLong ORDER_IDS = new AtomicLong(5_000_000);
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected RabbitTemplate rabbit;
    @Autowired
    protected BookingClient booking;
    @Autowired
    protected CatalogClient catalog;
    @Autowired
    protected UserDirectory users;
    @Autowired
    protected TicketStore store;
    @Autowired
    protected TicketMailer mailer;
    @Autowired
    protected TicketWorkflow workflow;
    @Autowired
    protected TicketRepository tickets;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MailpitContainer mailpit;

    @BeforeEach
    void cleanBrokerAndMailbox() {
        mailpit.clear();
        rabbit.execute(channel -> {
            channel.queuePurge("tickets");
            channel.queuePurge("tickets.dlq");
            return null;
        });
    }

    protected static long nextOrderId() {
        return ORDER_IDS.incrementAndGet();
    }

    /** Stubs booking, catalog and the user directory for one order (two seats, A1 and A2, INR 4,499 in total). */
    protected TicketRequested stubOrder(String userId, long orderId) {
        BookingOrder order = new BookingOrder(orderId, 3L, new BigDecimal("4499.00"), List.of(
                new BookingOrder.Line(11, "A", 1, new BigDecimal("2000.00")),
                new BookingOrder.Line(12, "A", 2, new BigDecimal("2499.00"))));
        doReturn(order).when(booking).order(userId, orderId);
        doReturn(new CatalogEvent("Coldplay Tribute Live", "JLN Stadium", "Asia/Kolkata",
                Instant.parse("2027-01-18T14:00:00Z"))).when(catalog).event(3L);
        doReturn(userId + "@example.com").when(users).email(userId);
        return new TicketRequested(UUID.randomUUID(), orderId, userId, 3L, List.of(11L, 12L),
                Instant.parse("2027-01-01T10:00:00Z"));
    }

    /** Publishes the way booking's outbox does: raw JSON, content type and a {@code type} header. */
    protected void publish(TicketRequested message) {
        publishRaw(JSON.writeValueAsString(message));
    }

    protected void publishRaw(String body) {
        Message amqp = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader("type", MessageTypes.TICKET_REQUESTED).build();
        rabbit.send(MessageTypes.TICKETS_EXCHANGE, MessageTypes.TICKET_REQUESTED_KEY, amqp);
    }

    protected long queueDepth(String queue) {
        Long count = rabbit.execute(channel -> channel.messageCount(queue));
        return count == null ? -1 : count;
    }
}
