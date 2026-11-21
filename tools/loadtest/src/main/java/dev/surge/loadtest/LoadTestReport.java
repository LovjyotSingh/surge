package dev.surge.loadtest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * One load-test run. {@code oversold} and {@code consistent} come from the server's integrity check (MySQL);
 * {@code buyersSucceeded} is counted on the client (buyers that got a 201, or a 200 {@code Idempotent-Replayed} answer
 * to a checkout they had to send again, for their one seat).
 * {@code unauthorized} counts buyers the gateway rejected (401/403); when it is above 0 the run is not valid.
 * {@code errors} counts buyers that ended on anything but a sale or an expected conflict on a HOLD (409 seat taken or
 * hold exists, which is {@code conflicts}): a 5xx, a timeout, too many 429s, a 503 service-busy the buyer gave up on,
 * a checkout still failing after its retries, and any 409 on the checkout (the buyer's own held seats cannot be sold
 * out, so the system lost the hold).
 * {@code busyRetries} counts the 503 service-busy answers (a full gateway bulkhead) that buyers waited on and retried,
 * like {@code rateLimited} for 429s; a retried busy answer is not an error (P2-R26). {@code hold} and {@code checkout}
 * have one latency per step (a hold attempt, the checkout), from its first send to its final answer, busy waits
 * included; {@code requests} counts every request sent, busy retries and checkout retries included.
 * {@code sold} is every SOLD seat of the event when the run ended; {@code soldBefore} is how many were SOLD before the
 * buyers were released (0 after a reset; more with {@code --no-reset} on an event with earlier sales). The run is judged
 * on {@link #soldInRun()} = {@code sold - soldBefore}, so earlier sales never fail it.
 * {@code seatMismatches} is the server's per-seat count (SOLD seats without an order line plus order lines on seats that
 * are not SOLD); {@code consistent} means it is 0.
 * {@code checkoutRetries} counts checkouts sent again with the same Idempotency-Key after a timeout, a 502/504, a 503
 * that was not service-busy, or a 409 request-in-progress.
 */
public record LoadTestReport(Instant startedAt, int users, long eventId, int totalSeats, int sold,
                             int oversold, boolean consistent, int buyersSucceeded, int requests, int conflicts,
                             int rateLimited, int busyRetries, int errors, long durationMs, LatencyStats hold,
                             LatencyStats checkout, int unauthorized, int soldBefore, int seatMismatches,
                             int checkoutRetries) {

    /** More buyers than this share of all buyers ending in an error fails the run. */
    public static final int MAX_ERROR_PERCENT = 1;

    /** A run on an event with no earlier sales and no checkout retries; mismatches are 0 when {@code consistent}. */
    public LoadTestReport(Instant startedAt, int users, long eventId, int totalSeats, int sold, int oversold,
                          boolean consistent, int buyersSucceeded, int requests, int conflicts, int rateLimited,
                          int busyRetries, int errors, long durationMs, LatencyStats hold, LatencyStats checkout,
                          int unauthorized) {
        this(startedAt, users, eventId, totalSeats, sold, oversold, consistent, buyersSucceeded, requests, conflicts,
            rateLimited, busyRetries, errors, durationMs, hold, checkout, unauthorized, 0, consistent ? 0 : 1, 0);
    }

    /** Seats sold by this run: the sold seats at the end minus those sold before the buyers started. */
    @JsonProperty("soldInRun")
    public int soldInRun() {
        return sold - soldBefore;
    }

    /**
     * The safety invariants: no seat has two order lines, every seat marked SOLD has an order line and every order line
     * is on a SOLD seat (checked seat by seat), and the number of successful checkouts the buyers saw equals the seats
     * this run sold (each buyer buys one seat, so every success is a distinct seat).
     */
    public boolean invariantsHold() {
        return oversold == 0 && consistent && buyersSucceeded == soldInRun();
    }

    /** The event had seats but this run sold none: it measured nothing (everything failed before a sale). */
    public boolean nothingSold() {
        return totalSeats > 0 && soldInRun() == 0;
    }

    /** More than {@link #MAX_ERROR_PERCENT} % of the buyers ended in an error (conflicts are not errors). */
    public boolean tooManyErrors() {
        return (long) errors * 100 > (long) users * MAX_ERROR_PERCENT;
    }

    /**
     * The run passes when the invariants hold AND it really sold: at least one seat, and errors within
     * {@link #MAX_ERROR_PERCENT} % of the buyers. A run that sold nothing because every request failed is not a pass.
     * Also in the report JSON as {@code passed}, so the Ops page shows this verdict instead of re-deriving it.
     */
    @JsonProperty("passed")
    public boolean passed() {
        return invariantsHold() && !nothingSold() && !tooManyErrors();
    }
}
