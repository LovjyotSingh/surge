package dev.surge.booking.ratelimit;

public record RateDecision(boolean allowed, long retryAfterSeconds) {
}
