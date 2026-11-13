package dev.surge.booking.error;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException() {
        super("Hold not found or expired");
    }
}
