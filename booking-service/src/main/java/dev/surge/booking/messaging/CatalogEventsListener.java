package dev.surge.booking.messaging;

import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.MessageTypes;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code catalog.v1}. The payload is parsed here (not in a deserializer) so a malformed message throws a
 * {@code JacksonException} inside the container, where the error handler sends it straight to the DLT.
 */
@Component
public class CatalogEventsListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventsListener.class);

    private final JsonMapper json;
    private final EventScheduledHandler handler;

    public CatalogEventsListener(JsonMapper json, EventScheduledHandler handler) {
        this.json = json;
        this.handler = handler;
    }

    @KafkaListener(topics = MessageTypes.CATALOG_TOPIC, groupId = "booking-catalog")
    public void onMessage(String payload, @Header(name = "type", required = false) byte[] type) {
        String typeName = type == null ? null : new String(type, StandardCharsets.UTF_8);
        if (typeName != null && !MessageTypes.EVENT_SCHEDULED.equals(typeName)) {
            log.debug("Ignoring catalog.v1 message of type {}", typeName);
            return;
        }
        handler.handle(json.readValue(payload, EventScheduled.class));
    }
}
