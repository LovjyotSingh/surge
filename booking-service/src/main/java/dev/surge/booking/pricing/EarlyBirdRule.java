package dev.surge.booking.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class EarlyBirdRule implements PricingRule {

    private static final BigDecimal FACTOR = new BigDecimal("0.90");
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Override
    public BigDecimal apply(BigDecimal current, PricingContext ctx) {
        if (ctx.now().isBefore(ctx.saleOpensAt().plus(WINDOW))) {
            return current.multiply(FACTOR);
        }
        return current;
    }
}
