package dev.surge.booking.error;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(long eventId) {
        super("Event " + eventId + " not found");
    }
}
