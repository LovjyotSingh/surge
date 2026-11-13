package dev.surge.booking.pricing;

import java.math.BigDecimal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class SurgeRule implements PricingRule {

    private static final BigDecimal FACTOR = new BigDecimal("1.20");

    @Override
    public BigDecimal apply(BigDecimal current, PricingContext ctx) {
        // Surge when fewer than 20% of seats remain.
        if ((long) ctx.availableSeats() * 5 < ctx.totalSeats()) {
            return current.multiply(FACTOR);
        }
        return current;
    }
}
