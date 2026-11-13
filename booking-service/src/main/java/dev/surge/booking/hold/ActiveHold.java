package dev.surge.booking.hold;

import java.time.Instant;
import java.util.List;

/** A live hold as stored in Redis. {@code member} is the holds:expiry sorted-set member. */
public record ActiveHold(String holdId, String userId, long eventId, List<Long> seatIds, Instant expiresAt,
                         String member) {
}
