package dev.surge.loadtest;

import java.time.Instant;

public record LoadTestReport(Instant startedAt, int users, long eventId, int totalSeats, int sold,
                             int oversold, int buyersSucceeded, int conflicts, int rateLimited,
                             int errors, long durationMs, LatencyStats hold, LatencyStats checkout) {
}
