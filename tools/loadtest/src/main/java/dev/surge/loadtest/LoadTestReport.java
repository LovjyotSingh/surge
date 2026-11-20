package dev.surge.loadtest;

import java.time.Instant;

/**
 * One load-test run. {@code oversold} and {@code consistent} come from the server's integrity check (MySQL);
 * {@code buyersSucceeded} is counted on the client (buyers that got a 201 for their one seat).
 * {@code unauthorized} counts buyers the gateway rejected (401/403); when it is above 0 the run is not valid.
 * {@code errors} counts buyers that ended on anything but a sale or an expected conflict (409 seat taken or sold out,
 * which is {@code conflicts}): a 5xx, a timeout, too many 429s.
 */
public record LoadTestReport(Instant startedAt, int users, long eventId, int totalSeats, int sold,
                             int oversold, boolean consistent, int buyersSucceeded, int requests, int conflicts,
                             int rateLimited, int errors, long durationMs, LatencyStats hold, LatencyStats checkout,
                             int unauthorized) {

    /** More buyers than this share of all buyers ending in an error fails the run. */
    public static final int MAX_ERROR_PERCENT = 1;

    /**
     * The safety invariants: no seat has two order lines, every SOLD seat has exactly one order line, and the number
     * of 201s the buyers saw equals the seats sold (each buyer buys one seat, so every 201 is a distinct seat).
     */
    public boolean invariantsHold() {
        return oversold == 0 && consistent && buyersSucceeded == sold;
    }

    /** The event had seats but none was sold: the run measured nothing (everything failed before a sale). */
    public boolean nothingSold() {
        return totalSeats > 0 && sold == 0;
    }

    /** More than {@link #MAX_ERROR_PERCENT} % of the buyers ended in an error (conflicts are not errors). */
    public boolean tooManyErrors() {
        return (long) errors * 100 > (long) users * MAX_ERROR_PERCENT;
    }

    /**
     * The run passes when the invariants hold AND it really sold: at least one seat, and errors within
     * {@link #MAX_ERROR_PERCENT} % of the buyers. A run that sold nothing because every request failed is not a pass.
     */
    public boolean passed() {
        return invariantsHold() && !nothingSold() && !tooManyErrors();
    }
}
