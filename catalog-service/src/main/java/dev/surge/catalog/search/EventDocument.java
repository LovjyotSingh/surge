package dev.surge.catalog.search;

import dev.surge.catalog.event.Event;
import dev.surge.catalog.event.EventView;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

/**
 * One event as it is stored in the {@code events} index. It carries everything {@link EventView} shows, so a search
 * answers from the index alone (no database round trip per hit). The index and its mapping are created from
 * {@code elasticsearch/events-mapping.json}, never from these annotations.
 */
@Document(indexName = EventIndexer.INDEX, createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record EventDocument(
        @Id Long id,
        String title,
        String artist,
        String category,
        String venueName,
        String city,
        String zoneId,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant startsAt,
        @Field(type = FieldType.Date, format = DateFormat.date_time) Instant saleOpensAt,
        BigDecimal minPriceInr,
        BigDecimal maxPriceInr,
        int totalSeats,
        int seatsLeft,
        String description,
        GeoPoint location) {

    static EventDocument of(Event e) {
        return new EventDocument(e.getId(), e.getTitle(), e.getArtist(), e.getCategory(), e.getVenue().getName(),
                e.getVenue().getCity(), e.getVenue().getZoneId(), e.getStartsAt(), e.getSaleOpensAt(),
                e.getMinPriceInr(), e.getMaxPriceInr(), e.getTotalSeats(), e.getSeatsLeft(), e.getDescription(),
                new GeoPoint(e.getVenue().getLatitude().doubleValue(), e.getVenue().getLongitude().doubleValue()));
    }

    EventView toView() {
        return new EventView(id, title, artist, category, venueName, city, zoneId, startsAt, saleOpensAt,
                minPriceInr, maxPriceInr, totalSeats, seatsLeft, description);
    }
}
