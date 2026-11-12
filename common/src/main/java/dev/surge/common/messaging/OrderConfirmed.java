package dev.surge.common.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderConfirmed(UUID messageId, long orderId, String userId, long eventId,
                             List<Long> seatIds, BigDecimal totalInr, Instant occurredAt) {
}
