package dev.surge.notification.catalog;

public class CatalogEventNotFoundException extends RuntimeException {
    public CatalogEventNotFoundException(long eventId) {
        super("Event " + eventId + " not found in catalog");
    }
}
