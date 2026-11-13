package dev.surge.booking.inventory;

import dev.surge.booking.event.Tier;

public record SeatInfo(long seatId, String section, String rowLabel, int number, Tier tier) {
}
