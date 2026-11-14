package dev.surge.notification.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.notification.error.ReplayFailedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looking into {@code tickets.dlq} and moving messages back. Both use {@code basicGet} with manual acks, so a
 * message is only ever removed after it has been safely handled.
 *
 * <p>Listing takes up to N messages without acknowledging them and then {@code nack}s them with requeue, which
 * puts them back where they were (AMQP 0-9-1 has no real peek). The unacked messages are hidden from other
 * consumers only while the listing runs. {@code RabbitTemplate.receive} would remove them and need a republish,
 * which could lose a message if the app died in between; the management HTTP API would add a second port and
 * credentials.
 */
@Component
public class DlqOperations {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitTemplate rabbit;

    public DlqOperations(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public record DlqMessage(String messageId, Long orderId, int deaths, String reason) {
    }

    public List<DlqMessage> list(int limit) {
        List<DlqMessage> out = new ArrayList<>();
        try (var connection = rabbit.getConnectionFactory().createConnection();
             Channel channel = connection.createChannel(false)) {
            long lastTag = -1;
            try {
                for (int i = 0; i < limit; i++) {
                    GetResponse response = channel.basicGet(TicketQueueConfig.DLQ, false);
                    if (response == null) {
                        break;
                    }
                    lastTag = response.getEnvelope().getDeliveryTag();
                    out.add(describe(response));
                }
            } finally {
                requeue(channel, lastTag);
            }
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("Could not list the dead-letter queue", e);
        }
        return out;
    }

    /**
     * Puts every unacked message of this dedicated channel back. If the nack itself fails (closed channel) the
     * original error must not be hidden; closing the channel requeues unacked messages anyway, which the
     * try-with-resources above does.
     */
    private static void requeue(Channel channel, long lastTag) {
        if (lastTag < 0) {
            return;
        }
        try {
            channel.basicNack(lastTag, true, true);
        } catch (IOException | RuntimeException ignored) {
            // Channel already gone: the broker requeues unacked messages when a channel closes.
        }
    }

    /**
     * Republishes up to {@code limit} messages (body and headers kept) to {@code surge.tickets} with the original
     * routing key. The DLQ copy is acked only when the broker confirmed the publish AND no {@code basic.return}
     * came back: for an unroutable mandatory message RabbitMQ sends the return first and then still acks, so a
     * confirm alone proves nothing. A returned message is nacked back into the DLQ and the call fails.
     * The channel is dedicated and closed afterwards (closing requeues anything left unacked).
     */
    public int replay(int limit) {
        int replayed = 0;
        try (var connection = rabbit.getConnectionFactory().createConnection();
             Channel channel = connection.createChannel(false)) {
            AtomicBoolean returned = new AtomicBoolean();
            channel.addReturnListener((code, text, exchange, key, props, body) -> returned.set(true));
            channel.confirmSelect();
            for (int i = 0; i < limit; i++) {
                GetResponse response = channel.basicGet(TicketQueueConfig.DLQ, false);
                if (response == null) {
                    break;
                }
                long tag = response.getEnvelope().getDeliveryTag();
                returned.set(false);
                try {
                    channel.basicPublish(MessageTypes.TICKETS_EXCHANGE, MessageTypes.TICKET_REQUESTED_KEY, true,
                            response.getProps(), response.getBody());
                    channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                } catch (IOException | TimeoutException e) {
                    requeue(channel, tag);
                    throw new IllegalStateException("Could not replay a dead-lettered ticket message", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    requeue(channel, tag);
                    throw new IllegalStateException("Interrupted while replaying a ticket message", e);
                }
                // The return is delivered before the confirm on the same channel, so the flag is final here.
                if (returned.get()) {
                    requeue(channel, tag);
                    throw new ReplayFailedException("The ticket queue is not reachable (message was returned unroutable)",
                            replayed);
                }
                channel.basicAck(tag, false);
                replayed++;
            }
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("Could not replay dead-lettered ticket messages", e);
        }
        return replayed;
    }

    private static DlqMessage describe(GetResponse response) {
        Map<String, Object> headers = response.getProps().getHeaders();
        String reason = headers == null ? null : text(headers.get("x-exception-message"));
        int deaths = 0;
        if (headers != null && headers.get("x-death") instanceof List<?> death && !death.isEmpty()
                && death.getFirst() instanceof Map<?, ?> first) {
            if (first.get("count") instanceof Number n) {
                deaths = n.intValue();
            }
            if (reason == null) {
                reason = text(first.get("reason"));
            }
        }
        String messageId = null;
        Long orderId = null;
        try {
            JsonNode body = JSON.readTree(response.getBody());
            if (body != null && body.isObject()) {
                messageId = body.hasNonNull("messageId") ? body.get("messageId").asString() : null;
                orderId = body.hasNonNull("orderId") && body.get("orderId").canConvertToLong()
                        ? body.get("orderId").asLong() : null;
            }
        } catch (RuntimeException notJson) {
            // The listing is for finding broken messages: an unreadable body simply has no ids.
        }
        return new DlqMessage(messageId, orderId, deaths, reason);
    }

    /** Header values arrive as strings or {@code LongString}s depending on how they were published. */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
