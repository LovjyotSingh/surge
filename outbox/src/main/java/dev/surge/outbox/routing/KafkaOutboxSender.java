package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the payload (already JSON) as a String record. The key is the payload's top-level {@code eventId} when
 * present (so one event's messages share a partition), otherwise the aggregate id. Headers: {@code type}, and
 * {@code messageId} when the payload carries one.
 */
public class KafkaOutboxSender implements OutboxSender {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> template;

    public KafkaOutboxSender(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Override
    public void send(OutboxMessage message, OutboxDestination destination) {
        String topic = ((OutboxDestination.Kafka) destination).topic();
        JsonNode payload = JSON.readTree(message.getPayload());
        String key = text(payload, "eventId");
        ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                key != null ? key : message.getAggregateId(), message.getPayload());
        record.headers().add("type", message.getType().getBytes(StandardCharsets.UTF_8));
        String messageId = text(payload, "messageId");
        if (messageId != null) {
            record.headers().add("messageId", messageId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            template.send(record).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to kafka topic " + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("kafka publish to " + topic + " failed", e);
        }
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
