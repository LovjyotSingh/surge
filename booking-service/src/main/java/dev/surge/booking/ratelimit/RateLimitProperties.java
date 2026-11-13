package dev.surge.booking.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("surge.rate-limit")
public record RateLimitProperties(Bucket holds) {

    public record Bucket(int capacity, double refillPerSecond) {
    }
}
