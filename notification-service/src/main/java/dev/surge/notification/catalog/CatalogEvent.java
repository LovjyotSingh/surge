package dev.surge.notification.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The slice of catalog's event view that a ticket needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogEvent(String title, String venueName, String zoneId, Instant startsAt) {

    /** The start time in the venue's own zone. */
    public ZonedDateTime startsAtLocal() {
        return startsAt.atZone(ZoneId.of(zoneId));
    }
}
