package dev.surge.booking.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PriceCalculator {

    private final List<PricingRule> rules;

    public PriceCalculator(List<PricingRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public BigDecimal price(PricingContext ctx) {
        BigDecimal current = ctx.basePrice();
        for (PricingRule rule : rules) {
            current = rule.apply(current, ctx);
        }
        return current.setScale(0, RoundingMode.HALF_UP);
    }
}
