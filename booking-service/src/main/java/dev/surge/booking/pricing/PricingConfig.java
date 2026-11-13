package dev.surge.booking.pricing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TierPrices.class)
public class PricingConfig {
}
