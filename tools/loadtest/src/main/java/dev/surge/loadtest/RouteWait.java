package dev.surge.loadtest;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Preamble before the run: right after {@code up --wait} the gateway's view of the Eureka registry can lag by up to a
 * minute, so booking and catalog answer 503 {@code service-unavailable} although every container is healthy. Waits
 * until neither does. Giving up is not a failure: the run proceeds and its verdict fails on the errors.
 */
final class RouteWait {

    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final Duration INTERVAL = Duration.ofSeconds(2);
    static final String CATALOG_PATH = "/api/catalog/events/search?q=coldply";

    private RouteWait() {
    }

    static void await(BookingApi api, long eventId, PrintStream out) {
        await(api, eventId, TIMEOUT, INTERVAL, out);
    }

    static void await(BookingApi api, long eventId, Duration timeout, Duration interval, PrintStream out) {
        String bookingPath = "/api/bookings/events/" + eventId;
        long t0 = System.nanoTime();
        long deadline = t0 + timeout.toNanos();
        String last;
        while (true) {
            last = api.unavailable(bookingPath);
            if (last == null) {
                last = api.unavailable(CATALOG_PATH);
            }
            if (last == null) {
                out.println("Waiting for the gateway to route to booking and catalog… ready after "
                    + (System.nanoTime() - t0) / 1_000_000_000L + " s");
                return;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(interval.toMillis(), Math.max(1, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                last = "interrupted";
                break;
            }
        }
        out.println("Waiting for the gateway to route to booking and catalog… gave up after " + timeout.toSeconds()
            + " s (last: " + last + ")");
    }
}
