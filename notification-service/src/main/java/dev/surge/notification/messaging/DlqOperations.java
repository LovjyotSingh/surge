package dev.surge.notification.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.notification.error.ReplayFailedException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
& {@code tickets.dlq} and moving messages back. Both use {@code basicGet} with manual acks, so a
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

    /**
     * A physical broker connection of its own (never the pooled one the listeners and publishers share): closing it
     * really closes the channel, so the broker requeues every delivery that is still unacked, and confirm mode and
     * return listeners cannot leak onto pooled channels. Admin calls are rare, so one connection per call is fine.
     */
    private Connection dedicatedConnection() throws IOException, TimeoutException {
        if (!(rabbit.getConnectionFactory() instanceof CachingConnectionFactory caching)) {
            throw new IllegalStateException("Expected a CachingConnectionFactory");
        }
        return caching.getRabbitConnectionFactory().newConnection("notification-dlq-admin");
    }

    public List<DlqMessage> list(int limit) {
        List<DlqMessage> out = new ArrayList<>();
        try (Connection connection = dedicatedConnection(); Channel channel = connection.createChannel()) {
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
     * Puts every unacked message of this channel back. If the nack itself fails (channel already dead) the original
     * error must not be hidden; the physical close of the dedicated connection requeues unacked messages anyway.
     */
    private static void requeue(Channel channel, long lastTag) {
        if (lastTag < 0) {
            return;
        }
        try {
            channel.basicNack(lastTag, true, true);
        } catch (IOException | RuntimeException ignored) {
            // Channel already gone: the broker requeues unacked messages when the channel closes.
        }
    }

    /**
     * Republishes up to {@code limit} messages (body and headers kept) to {@code surge.tickets} with the original
     * routing key. The DLQ copy is acked only when the broker confirmed the publish AND no {@code basic.return}
     * came back: for an unroutable mandatory message RabbitMQ sends the return first and then still acks, so a
     * confirm alone proves nothing. Any failure after the message was taken (return, broker nack, confirm timeout,
     * dead channel, any runtime error) puts it back in the DLQ and fails the call with {@link ReplayFailedException}:
     * nothing is lost, the broker or its topology is unhealthy.
     */
    public int replay(int limit) {
        int replayed = 0;
        try (Connection connection = dedicatedConnection(); Channel channel = connection.createChannel()) {
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
                    // The return is delivered before the confirm on the same channel, so the flag is final here.
                    if (returned.get()) {
                        throw new ReplayFailedException(
                                "The ticket queue is not reachable (message was returned unroutable)", replayed);
                    }
                    channel.basicAck(tag, false);
                } catch (ReplayFailedException e) {
                    requeue(channel, tag);
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    requeue(channel, tag);
                    throw new ReplayFailedException("Interrupted while replaying", replayed);
                } catch (IOException | TimeoutException | RuntimeException e) {
                    requeue(channel, tag);
                    throw new ReplayFailedException("The broker did not confirm the replay (" + e.getMessage() + ")",
                            replayed);
                }
                replayed++;
            }
        } catch (IOException | TimeoutException e) {
            throw new ReplayFailedException("Could not reach the broker (" + e.getMessage() + ")", replayed);
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
