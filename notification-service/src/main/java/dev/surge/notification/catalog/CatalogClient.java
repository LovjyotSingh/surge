package dev.surge.notification.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** Reads event details from catalog-service. Timeouts and I/O errors propagate (the caller retries). */
public class CatalogClient {

    private final RestClient client;

    public CatalogClient(RestClient client) {
        this.client = client;
    }

    public CatalogEvent event(long eventId) {
        CatalogEvent event = client.get().uri("/api/catalog/events/{id}", eventId).retrieve()
                .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    throw new CatalogEventNotFoundException(eventId);
                })
                .body(CatalogEvent.class);
        if (event == null) {
            throw new CatalogEventNotFoundException(eventId);
        }
        return event;
    }
}
