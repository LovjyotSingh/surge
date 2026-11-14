package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;

/** Sends one outbox message to one broker and returns only once the broker has accepted it; throws otherwise. */
public interface OutboxSender {
    void send(OutboxMessage message, OutboxDestination destination);
}
