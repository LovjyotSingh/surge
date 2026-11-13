package dev.surge.booking.pricing;

import java.math.BigDecimal;

@FunctionalInterface
public interface PricingRule {
    BigDecimal apply(BigDecimal current, PricingContext ctx);
}
