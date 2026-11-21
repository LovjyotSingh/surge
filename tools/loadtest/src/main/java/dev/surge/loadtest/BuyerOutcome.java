package dev.surge.loadtest;

import java.util.List;

/**
 * {@code unauthorized}: requests the gateway rejected with 401 or 403 (a bad, expired or not-allowed token).
 * {@code busyRetries}: 503 {@code service-busy} answers this buyer waited on and retried (P2-R26); a busy answer it gave
 * up on is in {@code errors} instead.
 * {@code checkoutRetries}: checkouts this buyer sent again with the same Idempotency-Key after a timeout, a 502/504, a
 * 503 that was not service-busy, or a 409 request-in-progress.
 */
public record BuyerOutcome(boolean bought, int conflicts, int rateLimited, int busyRetries, int checkoutRetries,
                           int errors, int unauthorized, List<Long> holdLatenciesMs, List<Long> checkoutLatenciesMs) {
}
