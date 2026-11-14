package dev.surge.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;

public record EventView(long id, String title, String artist, String category, String venueName, String city,
                        String zoneId, Instant startsAt, Instant saleOpensAt, BigDecimal minPriceInr,
                        BigDecimal maxPriceInr, int totalSeats, int seatsLeft, String description) {

    static EventView of(Event e) {
        return new EventView(e.getId(), e.getTitle(), e.getArtist(), e.getCategory(), e.getVenue().getName(),
                e.getVenue().getCity(), e.getVenue().getZoneId(), e.getStartsAt(), e.getSaleOpensAt(),
                e.getMinPriceInr(), e.getMaxPriceInr(), e.getTotalSeats(), e.getSeatsLeft(), e.getDescription());
    }
}
