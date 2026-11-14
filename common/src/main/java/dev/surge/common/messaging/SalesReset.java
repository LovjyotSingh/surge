package dev.surge.common.messaging;

import java.time.Instant;
import java.util.UUID;

/** Published by booking on {@code orders.v1} (key = eventId) when an event's sales are wiped by an admin reset. */
public record SalesReset(UUID messageId, long eventId, Instant occurredAt) {
}
