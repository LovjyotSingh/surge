package dev.surge.booking.error;

import java.util.List;

public class SeatsTakenException extends RuntimeException {
    private final List<Long> seatIds;

    public SeatsTakenException(List<Long> seatIds) {
        super("Seats already taken: " + seatIds);
        this.seatIds = seatIds;
    }

    public List<Long> seatIds() {
        return seatIds;
    }
}
