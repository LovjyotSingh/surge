package dev.surge.common.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketRequested(UUID messageId, long orderId, String userId, long eventId,
                              List<Long> seatIds, Instant occurredAt) {
}
