package dev.surge.booking.outbox;

/**
 * Delivers one outbox message to its destination. Throwing signals failure; the relay retries on the next poll.
 * Implementations must not be {@code @Transactional}: a throw from a transactional proxy would mark the relay's
 * transaction rollback-only and undo the rows already published in that batch.
 */
public interface OutboxPublisher {
    void publish(OutboxMessage message);
}
