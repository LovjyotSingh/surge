package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;
import dev.surge.outbox.OutboxPublisher;

/**
 * Publishes each outbox row to the broker named by its destination. A destination whose broker is not configured
 * throws rather than being dropped, so the relay keeps the row and stops the batch. Not {@code @Transactional}.
 */
public class RoutingOutboxPublisher implements OutboxPublisher {

    private final OutboxSender kafka;
    private final OutboxSender rabbit;

    public RoutingOutboxPublisher(OutboxSender kafka, OutboxSender rabbit) {
        this.kafka = kafka;
        this.rabbit = rabbit;
    }

    @Override
    public void publish(OutboxMessage message) {
        OutboxDestination destination = OutboxDestination.parse(message.getDestination());
        switch (destination) {
            case OutboxDestination.Kafka k -> require(kafka, "kafka", message).send(message, k);
            case OutboxDestination.Rabbit r -> require(rabbit, "rabbit", message).send(message, r);
        }
    }

    private static OutboxSender require(OutboxSender sender, String broker, OutboxMessage message) {
        if (sender == null) {
            throw new IllegalStateException("no " + broker + " sender configured for outbox destination "
                    + message.getDestination());
        }
        return sender;
    }
}
