package dev.surge.booking.hold;

import java.math.BigDecimal;

/** One held seat and the price locked for it when the hold was made. */
public record HoldLine(long seatId, BigDecimal priceInr) {
}
