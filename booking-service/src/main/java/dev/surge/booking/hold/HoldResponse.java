package dev.surge.booking.hold;

import java.time.Instant;
import java.util.List;

public record HoldResponse(String holdId, long eventId, List<Long> seatIds, Instant expiresAt) {
}
