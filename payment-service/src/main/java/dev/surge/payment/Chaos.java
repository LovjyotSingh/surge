package dev.surge.payment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** failureRate: share of new charges declined; errorRate: share answered with HTTP 500; latencyMs: delay first. */
public record Chaos(
        @DecimalMin("0") @DecimalMax("1") double failureRate,
        @Min(0) @Max(10_000) int latencyMs,
        @DecimalMin("0") @DecimalMax("1") double errorRate) {
    public static final Chaos OFF = new Chaos(0, 0, 0);
}
