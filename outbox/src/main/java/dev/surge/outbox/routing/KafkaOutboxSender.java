package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends the payload (already JSON) as a String record. The key is the payload's top-level {@code eventId} when
 * present (so one event's messages share a partition), otherwise the aggregate id. Headers: {@code type}, and
 * {@code messageId} when the payload carries one.
 */
public class KafkaOutboxSender implements OutboxSender {

    private static final long TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> template;

    public KafkaOutboxSender(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Override
    public void send(OutboxMessage message, OutboxDestination destination) {
        String topic = ((OutboxDestination.Kafka) destination).topic();
        
        String key = PayloadFields.scalar(message.getPayload(), "eventId");
        ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                key != null ? key : message.getAggregateId(), message.getPayload());
        record.headers().add("type", message.getType().getBytes(StandardCharsets.UTF_8));
        String messageId = PayloadFields.scalar(message.getPayload(), "messageId");
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

}
