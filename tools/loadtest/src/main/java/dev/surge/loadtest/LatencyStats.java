package dev.surge.loadtest;

import java.util.Collections;
import java.util.List;

public record LatencyStats(long p50, long p95, long p99, long max) {

    public static LatencyStats of(List<Long> millis) {
        if (millis.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0);
        }
        List<Long> sorted = millis.stream().sorted().toList();
        return new LatencyStats(rank(sorted, 50), rank(sorted, 95), rank(sorted, 99),
            Collections.max(sorted));
    }

    private static long rank(List<Long> sorted, int percentile) {
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(idx, 0));
    }
}
