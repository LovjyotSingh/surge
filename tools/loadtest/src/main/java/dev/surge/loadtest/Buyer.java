package dev.surge.loadtest;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** One simulated buyer: holds a seat (retrying on conflicts), then checks out. */
public final class Buyer implements Callable<BuyerOutcome> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RATE_LIMIT_WAITS = 30;

    private final String userId;
    private final long eventId;
    private final List<Long> seatIds;
    private final BookingApi api;
    private final CountDownLatch startGate;
    private final double hotShare;
    private final int maxAttempts;
    private final RandomGenerator rnd;

    public Buyer(String userId, long eventId, List<Long> seatIds, BookingApi api, CountDownLatch startGate,
                 double hotShare, int maxAttempts, RandomGenerator rnd) {
        this.userId = userId;
        this.eventId = eventId;
        this.seatIds = seatIds;
        this.api = api;
        this.startGate = startGate;
        this.hotShare = hotShare;
        this.maxAttempts = maxAttempts;
        this.rnd = rnd;
    }

    @Override
    public BuyerOutcome call() throws InterruptedException {
        startGate.await();
        int conflicts = 0;
        int rateLimited = 0;
        int errors = 0;
        List<Long> holdLat = new ArrayList<>();
        List<Long> checkoutLat = new ArrayList<>();
        try {
            String holdId = null;
            int attempts = 0;
            while (holdId == null && attempts < maxAttempts) {
                long seat = SeatPicker.pick(seatIds, hotShare, rnd);
                long t0 = System.nanoTime();
                HttpResponse<String> r = api.hold(userId, eventId, seat);
                holdLat.add(elapsedMs(t0));
                int status = r.statusCode();
                if (status == 201) {
                    holdId = JSON.readTree(r.body()).get("holdId").asString();
                } else if (status == 429) {
                    if (++rateLimited > MAX_RATE_LIMIT_WAITS) {
                        errors++;
                        break;
                    }
                    Thread.sleep(retryAfterSeconds(r) * 1000L);
                } else if (status == 409 && problemType(r).endsWith("/seat-taken")) {
                    conflicts++;
                    attempts++;
                } else if (status == 409 && problemType(r).endsWith("/hold-exists")) {
                    conflicts++;
                    break;
                } else {
                    errors++;
                    break;
                }
            }
            if (holdId == null) {
                return new BuyerOutcome(false, conflicts, rateLimited, errors, holdLat, checkoutLat);
            }
            long t0 = System.nanoTime();
            HttpResponse<String> order = api.checkout(userId, "lt-" + userId, holdId);
            checkoutLat.add(elapsedMs(t0));
            if (order.statusCode() == 201) {
                return new BuyerOutcome(true, conflicts, rateLimited, errors, holdLat, checkoutLat);
            }
            if (order.statusCode() == 409) {
                conflicts++;
            } else {
                errors++;
            }
        } catch (RuntimeException e) {
            errors++;
        }
        return new BuyerOutcome(false, conflicts, rateLimited, errors, holdLat, checkoutLat);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long retryAfterSeconds(HttpResponse<String> r) {
        try {
            return Math.max(1, Long.parseLong(r.headers().firstValue("Retry-After").orElse("1").trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String problemType(HttpResponse<String> r) {
        try {
            JsonNode type = JSON.readTree(r.body()).get("type");
            return type == null ? "" : type.asString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
