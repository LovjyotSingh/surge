package dev.surge.booking.hold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A live hold as stored in Redis. {@code prices} are the per-seat prices locked when the hold was made (checkout
 * charges exactly these). {@code member} is the holds:expiry sorted-set member.
 */
public record ActiveHold(String holdId, String userId, long eventId, List<Long> seatIds, Map<Long, BigDecimal> prices,
                         Instant expiresAt, String member) {
}
