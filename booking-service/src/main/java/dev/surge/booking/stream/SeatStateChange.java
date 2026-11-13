package dev.surge.booking.stream;

import dev.surge.booking.event.SeatState;
import java.util.List;

public record SeatStateChange(long eventId, List<Long> seatIds, SeatState state) {
}
