package dev.surge.booking.event;

import java.time.Instant;
import java.util.List;

public record EventDetail(long id, String title, String venueName, String zoneId, Instant saleOpensAt,
                          List<SeatView> seats) {
}
