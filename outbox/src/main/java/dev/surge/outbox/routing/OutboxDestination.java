package dev.surge.outbox.routing;

/** Parsed form of an outbox row's destination: {@code kafka:<topic>} or {@code rabbit:<exchange>/<routingKey>}. */
public sealed interface OutboxDestination {

    record Kafka(String topic) implements OutboxDestination {
    }

    record Rabbit(String exchange, String routingKey) implements OutboxDestination {
    }

    static OutboxDestination parse(String destination) {
        if (destination != null) {
            if (destination.startsWith("kafka:")) {
                String topic = destination.substring("kafka:".length());
                if (!topic.isBlank()) {
                    return new Kafka(topic);
                }
            } else if (destination.startsWith("rabbit:")) {
                String rest = destination.substring("rabbit:".length());
                int slash = rest.indexOf('/');
                if (slash > 0 && slash < rest.length() - 1) {
                    return new Rabbit(rest.substring(0, slash), rest.substring(slash + 1));
                }
            }
        }
        throw new IllegalArgumentException("unsupported outbox destination: " + destination);
    }
}
