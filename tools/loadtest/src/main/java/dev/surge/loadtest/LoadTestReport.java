package dev.surge.loadtest;

import java.time.Instant;

/**
 * One load-test run. {@code oversold} and {@code consistent} come from the server's integrity check (MySQL);
 * {@code buyersSucceeded} is counted on the client (buyers that got a 201 for their one seat).
 * {@code unauthorized} counts buyers the gateway rejected (401/403); when it is above 0 the run is not valid.
 */
public record LoadTestReport(Instant startedAt, int users, long eventId, int totalSeats, int sold,
                             int oversold, boolean consistent, int buyersSucceeded, int requests, int conflicts,
                             int rateLimited, int errors, long durationMs, LatencyStats hold, LatencyStats checkout,
                             int unauthorized) {

    /**
     * The run passes when no seat has two order lines, every SOLD seat has exactly one order line, and the number of
     * 201s the buyers saw equals the seats sold (each buyer buys one seat, so every 201 is a distinct seat).
     */
    public boolean passed() {
        return oversold == 0 && consistent && buyersSucceeded == sold;
    }
}
