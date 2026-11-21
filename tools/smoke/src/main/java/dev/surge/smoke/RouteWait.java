package dev.surge.smoke;

import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preamble before the numbered checks: right after {@code up --wait} the gateway's view of the Eureka registry can lag
 * by up to a minute, so booking and catalog answer 503 {@code service-unavailable} although every container is
 * healthy. Waits until neither does. Giving up is not a failure: the checks then report the real errors.
 */
public final class RouteWait {

    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final Duration INTERVAL = Duration.ofSeconds(2);
    static final String CATALOG_PATH = "/api/catalog/events/search?q=coldply";

    private RouteWait() {
    }

    public static void await(SmokeContext ctx, PrintStream out) {
        await(ctx.api(), ctx.eventId(), TIMEOUT, INTERVAL, out);
    }

    static void await(Api api, long eventId, Duration timeout, Duration interval, PrintStream out) {
        String bookingPath = "/api/bookings/events/" + eventId;
        AtomicReference<String> last = new AtomicReference<>("unknown");
        long t0 = System.nanoTime();
        String result;
        try {
            Poll.until("the gateway to route", timeout, interval, () -> {
                String bad = unavailable(api, bookingPath);
                if (bad == null) {
                    bad = unavailable(api, CATALOG_PATH);
                }
                if (bad != null) {
                    last.set(bad);
                    return null;
                }
                return Boolean.TRUE;
            });
            result = "ready after " + (System.nanoTime() - t0) / 1_000_000_000L + " s";
        } catch (CheckFailure e) {
            result = "gave up after " + timeout.toSeconds() + " s (last: " + last.get() + ")";
        }
        out.println("Waiting for the gateway to route to booking and catalog… " + result);
    }

    /** "GET path: HTTP 503 service-unavailable" while the route is not there yet, else null (any other answer). */
    private static String unavailable(Api api, String path) {
        try {
            Api.Resp r = api.get(path, null);
            if (r.status() == 503 && r.problem().endsWith("service-unavailable")) {
                return "GET " + path + ": " + r.describe();
            }
        } catch (CheckFailure e) {
            // Unreachable is not "not routed yet": the checks will report it.
        }
        return null;
    }
}
