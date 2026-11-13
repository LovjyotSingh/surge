package dev.surge.booking.inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record EventInventory(long eventId, String title, String venueName, Instant saleOpensAt, ZoneId zoneId,
                             Map<Long, SeatInfo> seats) {

    public EventInventory {
        seats = Collections.unmodifiableMap(new LinkedHashMap<>(seats));
    }

    public int totalSeats() {
        return seats.size();
    }

    public boolean contains(long seatId) {
        return seats.containsKey(seatId);
    }
}
