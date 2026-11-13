package dev.surge.booking.pricing;

import dev.surge.booking.event.Tier;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("surge.pricing")
public class TierPrices {

    private EnumMap<Tier, BigDecimal> tierPrices = new EnumMap<>(Tier.class);

    public Map<Tier, BigDecimal> getTierPrices() {
        return tierPrices;
    }

    public void setTierPrices(Map<Tier, BigDecimal> tierPrices) {
        this.tierPrices = new EnumMap<>(tierPrices);
    }

    public BigDecimal base(Tier tier) {
        BigDecimal price = tierPrices.get(tier);
        if (price == null) {
            throw new IllegalStateException("No base price configured for tier " + tier);
        }
        return price;
    }
}
