package dev.surge.booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.event.Tier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    Instant opens = Instant.parse("2026-11-12T10:00:00Z");
    PriceCalculator calc = new PriceCalculator(List.of(new EarlyBirdRule(), new SurgeRule()));

    PricingContext ctx(Instant now, int available) {
        return new PricingContext(Tier.GOLD, new BigDecimal("2999"), now, opens, available, 500);
    }

    @Test
    void noRulesApply() {
        assertThat(calc.price(ctx(opens.plus(Duration.ofMinutes(20)), 400))).isEqualByComparingTo("2999");
    }

    @Test
    void earlyBirdOnly() {
        // 2699.1 -> 2699
        assertThat(calc.price(ctx(opens.plus(Duration.ofMinutes(5)), 400))).isEqualByComparingTo("2699");
    }

    @Test
    void surgeOnly() {
        // 3598.8 -> 3599
        assertThat(calc.price(ctx(opens.plus(Duration.ofMinutes(20)), 99))).isEqualByComparingTo("3599");
    }

    @Test
    void earlyBirdThenSurge() {
        // 3238.92 -> 3239
        assertThat(calc.price(ctx(opens.plus(Duration.ofMinutes(5)), 99))).isEqualByComparingTo("3239");
    }

    @Test
    void earlyBirdEndsAt15Min() {
        assertThat(calc.price(ctx(opens.plus(Duration.ofMinutes(15)), 400))).isEqualByComparingTo("2999");
    }

    @Test
    void surgeBoundary() {
        // exactly 20% is not < 20%
        assertThat(calc.price(ctx(opens.plus(Duration.ofHours(1)), 100))).isEqualByComparingTo("2999");
    }
}
