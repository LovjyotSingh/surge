package dev.surge.catalog.event;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base price per tier, used only to show a min/max price on an event. Mirrors booking's {@code surge.pricing}. */
@ConfigurationProperties("surge.pricing")
public record PricingProperties(Map<Tier, BigDecimal> tierPrices) {

    public BigDecimal price(Tier tier) {
        BigDecimal price = tierPrices == null ? null : tierPrices.get(tier);
        if (price == null) {
            throw new IllegalStateException("No price configured for tier " + tier);
        }
        return price;
    }
}
