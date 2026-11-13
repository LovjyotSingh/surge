package dev.surge.booking.pricing;

import dev.surge.booking.event.Tier;
import java.math.BigDecimal;
import java.time.Instant;

public record PricingContext(
        Tier tier,
        BigDecimal basePrice,
        Instant now,
        Instant saleOpensAt,
        int availableSeats,
        int totalSeats) {
}
