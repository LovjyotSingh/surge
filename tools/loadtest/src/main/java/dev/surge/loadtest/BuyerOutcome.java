package dev.surge.loadtest;

import java.util.List;

/** {@code unauthorized}: requests the gateway rejected with 401 or 403 (a bad, expired or not-allowed token). */
public record BuyerOutcome(boolean bought, int conflicts, int rateLimited, int errors, int unauthorized,
                           List<Long> holdLatenciesMs, List<Long> checkoutLatenciesMs) {
}
