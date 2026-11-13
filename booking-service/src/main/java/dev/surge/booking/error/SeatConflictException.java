package dev.surge.booking.error;

public class SeatConflictException extends RuntimeException {
    public SeatConflictException() {
        super("Seats are no longer available");
    }
}
