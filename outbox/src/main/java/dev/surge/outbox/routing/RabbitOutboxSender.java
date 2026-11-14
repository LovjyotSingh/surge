package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends the JSON payload as a raw message (content type {@code application/json}, header {@code type}) and blocks
 * until the broker confirms it. An unroutable message (no queue bound) or a nack is a failure, so the outbox row
 * stays unpublished. Requires {@code publisher-confirm-type=correlated}, {@code publisher-returns=true} and
 * {@code template.mandatory=true}.
 */
public class RabbitOutboxSender implements OutboxSender {

    private static final long DEFAULT_CONFIRM_TIMEOUT_MILLIS = 5000;

    private final RabbitTemplate template;
    private final long confirmTimeoutMillis;

    public RabbitOutboxSender(RabbitTemplate template) {
        this(template, DEFAULT_CONFIRM_TIMEOUT_MILLIS);
    }

    RabbitOutboxSender(RabbitTemplate template, long confirmTimeoutMillis) {
        this.template = template;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Override
    public void send(OutboxMessage message, OutboxDestination destination) {
        var rabbit = (OutboxDestination.Rabbit) destination;
        String where = rabbit.exchange() + "/" + rabbit.routingKey();
        Message amqp = MessageBuilder.withBody(message.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader("type", message.getType())
                .build();
        String id = PayloadFields.scalar(message.getPayload(), "messageId");
        CorrelationData correlation = new CorrelationData(id != null ? id : message.getAggregateId());
        template.send(rabbit.exchange(), rabbit.routingKey(), amqp, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            // Spring AMQP sets 'returned' before it completes the future, so this check cannot miss a return.
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                throw new IllegalStateException("rabbit message to " + where + " was returned unroutable: "
                        + returned.getReplyCode() + " " + returned.getReplyText());
            }
            if (!confirm.isAck()) {
                throw new IllegalStateException("rabbit broker nacked message to " + where + ": " + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to rabbit " + where, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("rabbit publish to " + where + " failed or timed out", e);
        }
    }
}
