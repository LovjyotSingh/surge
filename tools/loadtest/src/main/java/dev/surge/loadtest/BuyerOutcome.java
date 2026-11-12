package dev.surge.loadtest;

import java.util.List;

public record BuyerOutcome(boolean bought, int conflicts, int rateLimited, int errors,
                           List<Long> holdLatenciesMs, List<Long> checkoutLatenciesMs) {
}
