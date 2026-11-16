package dev.surge.booking.error;

/** Too many seat streams are open (for this event, or in total): 503 {@code stream-capacity}. */
public class StreamCapacityException extends RuntimeException {

    public StreamCapacityException(String message) {
        super(message);
    }
}
