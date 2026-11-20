package dev.surge.loadtest;

import java.util.List;

/**
 * {@code unauthorized}: requests the gateway rejected with 401 or 403 (a bad, expired or not-allowed token).
 * {@code busyRetries}: 503 {@code service-busy} answers this buyer waited on and retried (P2-R26); a busy answer it gave
 * up on is in {@code errors} instead.
 */
public record BuyerOutcome(boolean bought, int conflicts, int rateLimited, int busyRetries, int errors,
                           int unauthorized, List<Long> holdLatenciesMs, List<Long> checkoutLatenciesMs) {
}
