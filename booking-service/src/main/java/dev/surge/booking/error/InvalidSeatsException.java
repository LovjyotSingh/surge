package dev.surge.booking.error;

import java.util.List;

public class InvalidSeatsException extends RuntimeException {
    private final List<Long> seatIds;

    public InvalidSeatsException(List<Long> seatIds) {
        super("Duplicate or unknown seats for this event: " + seatIds);
        this.seatIds = seatIds;
    }

    public List<Long> seatIds() {
        return seatIds;
    }
}
