package dev.surge.booking.event;

import java.time.Instant;

public record EventSummary(long id, String title, String venueName, String zoneId, Instant saleOpensAt,
                           int totalSeats, int soldSeats) {
}
