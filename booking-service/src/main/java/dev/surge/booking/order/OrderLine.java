package dev.surge.booking.order;

import java.math.BigDecimal;

public record OrderLine(long seatId, String rowLabel, int number, BigDecimal priceInr) {
}
