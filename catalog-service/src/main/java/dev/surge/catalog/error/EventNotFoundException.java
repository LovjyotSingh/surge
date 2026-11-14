package dev.surge.catalog.error;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(long id) {
        super("No event with id " + id);
    }
}
