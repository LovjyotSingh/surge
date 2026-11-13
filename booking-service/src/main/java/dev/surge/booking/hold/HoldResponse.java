package dev.surge.booking.hold;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** {@code quotedTotalInr} is the sum of {@code lines}: the exact amount checkout will charge for this hold. */
public record HoldResponse(String holdId, long eventId, List<Long> seatIds, Instant expiresAt,
                           BigDecimal quotedTotalInr, List<HoldLine> lines) {
}
