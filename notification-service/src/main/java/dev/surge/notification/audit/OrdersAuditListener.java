package dev.surge.notification.audit;

import dev.surge.common.messaging.MessageTypes;
import java.nio.charset.StandardCharsets;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audits every orders.v1 message (any type, unknown ones included) in its own consumer group, so it sees each
 * message independently of catalog. The id comes from the {@code messageId} header, else from the payload. A
 * payload that is not JSON, or a message with no usable id, can never succeed and is dead-lettered without retries.
 */
@Component
public class OrdersAuditListener {

    private static final int MAX_ID_LENGTH = 36;

    private final AuditRecorder recorder;
    private final JsonMapper json;

    public OrdersAuditListener(AuditRecorder recorder, JsonMapper json) {
        this.recorder = recorder;
        this.json = json;
    }

    @KafkaListener(topics = MessageTypes.ORDERS_TOPIC, groupId = "notification-audit",
            containerFactory = AuditKafkaConfig.CONTAINER_FACTORY, properties = "auto.offset.reset=earliest")
    public void onMessage(String payload, @Header(name = "type", required = false) byte[] type,
                          @Header(name = "messageId", required = false) byte[] messageId) {
        String id = messageId == null ? null : new String(messageId, StandardCharsets.UTF_8).trim();
        if (id == null || id.isEmpty()) {
            JsonNode node = json.readTree(payload);
            id = node.path("messageId").asString(null);
            id = id == null ? null : id.trim();
        }
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("orders.v1 message has no usable messageId");
        }
        String name = type == null ? "" : new String(type, StandardCharsets.UTF_8);
        recorder.record(id, name.isBlank() ? "unknown" : name, payload);
    }
}
