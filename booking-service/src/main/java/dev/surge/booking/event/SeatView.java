package dev.surge.booking.event;

import java.math.BigDecimal;

public record SeatView(long id, String rowLabel, int number, Tier tier, BigDecimal basePriceInr, SeatState state) {
}
