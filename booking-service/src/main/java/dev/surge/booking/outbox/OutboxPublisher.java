package dev.surge.booking.outbox;

/** Delivers one outbox message to its destination. Throwing signals failure; the relay retries on the next poll. */
public interface OutboxPublisher {
    void publish(OutboxMessage message);
}
