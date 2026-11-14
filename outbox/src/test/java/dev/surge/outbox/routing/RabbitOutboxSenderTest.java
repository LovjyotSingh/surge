package dev.surge.outbox.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.surge.outbox.OutboxMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitOutboxSenderTest {

    private static final OutboxDestination.Rabbit DEST = new OutboxDestination.Rabbit("surge.tickets", "ticket.requested");
    private static final OutboxMessage MESSAGE = new OutboxMessage("7", "TicketRequested", "rabbit:surge.tickets/ticket.requested",
            "{\"messageId\":\"m-1\",\"orderId\":7}", Instant.now());

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final RabbitOutboxSender sender = new RabbitOutboxSender(template, 200);

    private void onSend(java.util.function.Consumer<CorrelationData> action) {
        doAnswer(inv -> {
            action.accept(inv.getArgument(3));
            return null;
        }).when(template).send(eq("surge.tickets"), eq("ticket.requested"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void ackedMessageSucceedsAndUsesMessageIdAsCorrelationId() {
        var seen = new String[1];
        onSend(c -> {
            seen[0] = c.getId();
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        sender.send(MESSAGE, DEST);

        assertThat(seen[0]).isEqualTo("m-1");
    }

    @Test
    void correlationIdFallsBackToAggregateId() {
        var seen = new String[1];
        onSend(c -> {
            seen[0] = c.getId();
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        sender.send(new OutboxMessage("agg-9", "T", "rabbit:surge.tickets/ticket.requested", "not json", Instant.now()), DEST);

        assertThat(seen[0]).isEqualTo("agg-9");
    }

    @Test
    void nackThrowsWithReason() {
        onSend(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "disk full")));

        assertThatIllegalStateException().isThrownBy(() -> sender.send(MESSAGE, DEST)).withMessageContaining("disk full");
    }

    @Test
    void returnedMessageThrowsEvenWhenAcked() {
        onSend(c -> {
            c.setReturned(new ReturnedMessage(mock(Message.class), 312, "NO_ROUTE", "surge.tickets", "ticket.requested"));
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        assertThatIllegalStateException().isThrownBy(() -> sender.send(MESSAGE, DEST))
                .withMessageContaining("NO_ROUTE").withMessageContaining("312");
    }

    @Test
    void timeoutThrows() {
        onSend(c -> { });

        assertThatIllegalStateException().isThrownBy(() -> sender.send(MESSAGE, DEST))
                .withMessageContaining("timed out");
    }
}
