package dev.surge.booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.event.Tier;
import java.math.BigDecimal;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class TierPricesTest {

    @Test
    void baseReturnsConfiguredValueAndThrowsForMissingTier() {
        EnumMap<Tier, BigDecimal> prices = new EnumMap<>(Tier.class);
        prices.put(Tier.PLATINUM, new BigDecimal("4999"));
        prices.put(Tier.GOLD, new BigDecimal("2999"));
        TierPrices tierPrices = new TierPrices();
        tierPrices.setTierPrices(prices);

        assertThat(tierPrices.base(Tier.GOLD)).isEqualByComparingTo("2999");

        TierPrices missing = new TierPrices();
        missing.setTierPrices(new EnumMap<>(Tier.class));
        assertThatThrownBy(() -> missing.base(Tier.SILVER)).isInstanceOf(IllegalStateException.class);
    }
}
