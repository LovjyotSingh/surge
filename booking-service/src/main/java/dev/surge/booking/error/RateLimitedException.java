package dev.surge.booking.error;

public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Too many requests; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
