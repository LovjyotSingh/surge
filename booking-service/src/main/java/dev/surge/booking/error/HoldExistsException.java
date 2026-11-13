package dev.surge.booking.error;

public class HoldExistsException extends RuntimeException {
    private final String holdId;

    public HoldExistsException(String holdId) {
        super("User already has an active hold for this event");
        this.holdId = holdId;
    }

    public String holdId() {
        return holdId;
    }
}
