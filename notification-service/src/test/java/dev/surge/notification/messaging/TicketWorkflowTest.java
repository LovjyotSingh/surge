package dev.surge.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.messaging.TicketRequested;
import dev.surge.notification.support.Identity;
import dev.surge.notification.support.IntegrationTest;
import dev.surge.notification.support.TicketTestBase;
import dev.surge.notification.ticket.Ticket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class TicketWorkflowTest extends TicketTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private JsonNode awaitOneEmail() {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(mailpit.messages()).hasSize(1));
        return mailpit.messages().getFirst();
    }

    private int processedRows(TicketRequested message) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ? AND consumer = 'tickets'",
                Integer.class, message.messageId().toString());
    }

    @Test
    void ticketRequestedProducesPdfEmailAndRow() {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);

        publish(message);

        JsonNode summary = awaitOneEmail();
        assertThat(summary.get("Subject").asString()).isEqualTo("Your Surge tickets — Coldplay Tribute Live");
        assertThat(summary.get("To").get(0).get("Address").asString()).isEqualTo("u-1@example.com");

        JsonNode mail = mailpit.message(summary.get("ID").asString());
        assertThat(mail.get("From").get("Address").asString()).isEqualTo("tickets@surge.local");
        String text = mail.get("Text").asString();
        assertThat(text).contains("Coldplay Tribute Live").contains("JLN Stadium").contains("A1").contains("A2")
                .contains("INR 4,499").contains("19:30")
                .contains("http://localhost:8080/tickets/" + orderId);
        JsonNode attachment = mail.get("Attachments").get(0);
        assertThat(attachment.get("FileName").asString()).isEqualTo("surge-ticket-" + orderId + ".pdf");
        assertThat(attachment.get("ContentType").asString()).isEqualTo("application/pdf");
        byte[] mailedPdf = mailpit.attachment(summary.get("ID").asString(), attachment.get("PartID").asString());
        assertThat(new String(mailedPdf, 0, 5)).isEqualTo("%PDF-");

        byte[] stored = store.get(orderId).orElseThrow();
        assertThat(stored).isEqualTo(mailedPdf);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Ticket row = tickets.findById(orderId).orElseThrow();
            assertThat(row.getUserId()).isEqualTo("u-1");
            assertThat(row.getEventId()).isEqualTo(3L);
            assertThat(row.getS3Key()).isEqualTo("tickets/" + orderId + ".pdf");
            assertThat(row.getEmailedAt()).isNotNull();
        });
        assertThat(processedRows(message)).isEqualTo(1);
    }

    @Test
    void duplicateMessageIsIgnored() {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);
        long markerOrder = nextOrderId();
        TicketRequested marker = stubOrder("u-2", markerOrder);

        publish(message);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(tickets.findById(orderId)).hasValueSatisfying(t -> assertThat(t.getEmailedAt()).isNotNull()));
        publish(message);
        publish(marker);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(tickets.findById(markerOrder)).hasValueSatisfying(t -> assertThat(t.getEmailedAt()).isNotNull()));
        await().atMost(TIMEOUT).untilAsserted(() -> verify(workflow, times(3)).process(any()));

        assertThat(mailpit.messages()).hasSize(2);
        verify(store, times(1)).put(eq(orderId), any());
        assertThat(processedRows(message)).isEqualTo(1);
    }

    @Test
    void workflowReportsTheSecondCallAsDuplicate() {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);

        assertThat(workflow.process(message)).isTrue();
        assertThat(workflow.process(message)).isFalse();

        assertThat(mailpit.messages()).hasSize(1);
        verify(store, times(1)).put(eq(orderId), any());
    }

    @Test
    void poisonMessageGoesToDlqAfterFiveAttempts() throws Exception {
        long badOrder = nextOrderId();
        TicketRequested bad = stubOrder("u-1", badOrder);
        long goodOrder = nextOrderId();
        TicketRequested good = stubOrder("u-2", goodOrder);
        doThrow(new IllegalStateException("s3 is down")).when(store).put(eq(badOrder), any());

        publish(bad);
        publish(good);

        JsonNode summary = awaitOneEmail();
        assertThat(summary.get("To").get(0).get("Address").asString()).isEqualTo("u-2@example.com");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));
        verify(store, times(5)).put(eq(badOrder), any());
        assertThat(queueDepth("tickets")).isZero();
        // The failed attempts left nothing behind: no row, no claim, no email.
        assertThat(tickets.findById(badOrder)).isEmpty();
        assertThat(processedRows(bad)).isZero();
        assertThat(mailpit.messages()).hasSize(1);
        assertThat(processedRows(good)).isEqualTo(1);
        // The admin listing shows it, with the broker's dead-letter count, and does not consume it.
        mvc.perform(get("/api/notifications/admin/dlq").with(Identity.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].orderId").value((int) badOrder))
                .andExpect(jsonPath("$[0].messageId").value(bad.messageId().toString()))
                .andExpect(jsonPath("$[0].deaths").value(1));
        assertThat(queueDepth("tickets.dlq")).isEqualTo(1);
    }

    @Test
    void invalidMessageGoesToDlqWithoutRetry() {
        TicketRequested valid = stubOrder("u-1", nextOrderId());
        List<String> bodies = List.of(
                "{this is not json",
                JSON.writeValueAsString(new TicketRequested(valid.messageId(), valid.orderId(), "../etc", 3L,
                        valid.seatIds(), valid.occurredAt())),
                JSON.writeValueAsString(new TicketRequested(null, valid.orderId(), "u-1", 3L, valid.seatIds(),
                        valid.occurredAt())),
                JSON.writeValueAsString(new TicketRequested(UUID.randomUUID(), valid.orderId(), "u-1", 3L, List.of(),
                        valid.occurredAt())),
                JSON.writeValueAsString(new TicketRequested(UUID.randomUUID(), 0, "u-1", 3L, valid.seatIds(),
                        valid.occurredAt())),
                JSON.writeValueAsString(new TicketRequested(UUID.randomUUID(), valid.orderId(), "u-1", 0L, valid.seatIds(),
                        valid.occurredAt())));

        bodies.forEach(this::publishRaw);
        // A body that is not JSON by content type: the spring-messaging conversion error, never reaches the workflow.
        rabbit.send(dev.surge.common.messaging.MessageTypes.TICKETS_EXCHANGE,
                dev.surge.common.messaging.MessageTypes.TICKET_REQUESTED_KEY, org.springframework.amqp.core.MessageBuilder
                .withBody("plain text, not a ticket".getBytes()).setContentType("text/plain").build());

        // A retry would take 1 s, 2 s, 4 s and 8 s per message: all of them being dead-lettered fast means none was retried.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queueDepth("tickets.dlq")).isEqualTo(bodies.size() + 1));
        // One workflow call per message that parsed into a record, never a second.
        verify(workflow, times(5)).process(any());
        verify(booking, never()).order(any(), anyLong());
        verify(store, never()).put(anyLong(), any());
        assertThat(mailpit.messages()).isEmpty();
    }

    @Test
    void failedEmailRollsBackAndTheRetrySendsExactlyOne() {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);
        doThrow(new MailSendException("smtp is down")).doCallRealMethod().when(mailer).send(any(), any(), any());

        publish(message);

        awaitOneEmail();
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(tickets.findById(orderId)).hasValueSatisfying(t -> assertThat(t.getEmailedAt()).isNotNull()));
        verify(mailer, times(2)).send(any(), any(), any());
        verify(store, times(2)).put(eq(orderId), any());
        assertThat(queueDepth("tickets.dlq")).isZero();
        assertThat(mailpit.messages()).hasSize(1);
    }

    @Test
    void replayFromDlqDeliversTicket() throws Exception {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);
        doThrow(new IllegalStateException("s3 is down")).when(store).put(eq(orderId), any());

        publish(message);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));
        assertThat(mailpit.messages()).isEmpty();

        // S3 is back.
        doCallRealMethod().when(store).put(eq(orderId), any());
        mvc.perform(post("/api/notifications/admin/dlq/replay?limit=10").with(Identity.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));

        JsonNode summary = awaitOneEmail();
        assertThat(summary.get("To").get(0).get("Address").asString()).isEqualTo("u-1@example.com");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(tickets.findById(orderId)).isPresent());
        assertThat(queueDepth("tickets.dlq")).isZero();
        assertThat(store.get(orderId)).isPresent();
    }

    @Autowired
    MockMvc mvc;
}
