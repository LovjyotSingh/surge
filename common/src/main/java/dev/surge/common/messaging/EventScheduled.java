package dev.surge.common.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published by catalog on {@code catalog.v1} (key = eventId) when an event is created or scheduled. */
public record EventScheduled(UUID messageId, long eventId, String title, String venueName, String city,
                             String zoneId, Instant startsAt, Instant saleOpensAt, List<RowSpec> rows,
                             Instant occurredAt) {
}
