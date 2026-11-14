package dev.surge.catalog.messaging;

import dev.surge.catalog.search.EventIndexer;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.OrderConfirmed;
import dev.surge.common.messaging.SalesReset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes orders.v1. Payloads are parsed here so a malformed one throws inside the container and goes straight to
 * the dead-letter topic. The search index is touched only after the MySQL transaction has committed.
 */
@Component
public class OrdersListener {

    private static final Logger log = LoggerFactory.getLogger(OrdersListener.class);

    private final JsonMapper json;
    private final SalesHandler handler;
    private final EventIndexer indexer;

    public OrdersListener(JsonMapper json, SalesHandler handler, EventIndexer indexer) {
        this.json = json;
        this.handler = handler;
        this.indexer = indexer;
    }

    @KafkaListener(topics = MessageTypes.ORDERS_TOPIC, groupId = "catalog-orders")
    public void onMessage(String payload, @Header(name = "type", required = false) byte[] type) {
        String name = type == null ? null : new String(type, StandardCharsets.UTF_8);
        Optional<SeatsChange> change;
        if (MessageTypes.ORDER_CONFIRMED.equals(name)) {
            change = handler.handle(json.readValue(payload, OrderConfirmed.class));
        } else if (MessageTypes.SALES_RESET.equals(name)) {
            change = handler.handle(json.readValue(payload, SalesReset.class));
        } else {
            log.debug("Ignoring orders.v1 message of type {}", name);
            return;
        }
        change.ifPresent(c -> indexer.updateSeatsLeft(c.eventId(), c.seatsLeft()));
    }
}
