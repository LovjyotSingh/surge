package dev.surge.loadtest;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One simulated buyer: holds a seat (retrying on conflicts), then checks out.
 *
 * <p>Answers it waits on and repeats, like a real client: 429 (rate limited) after its {@code Retry-After}, and
 * (P2-R26) 503 {@code service-busy}, the gateway's answer when a service already has as many calls as it may (its
 * bulkhead is full; booking never saw the request). A busy step is sent again unchanged (same seat, same
 * {@code Idempotency-Key}) after {@code Retry-After} (default 1 s) plus 0-{@value #BUSY_JITTER_MS} ms of jitter, so
 * the refused buyers do not all come back in the same millisecond. A buyer gives up, which is an error, after
 * {@value #MAX_BUSY_RETRIES} busy retries or when the next one would end after the run's deadline. Any other 503 is
 * an error at once.
 */
public final class Buyer implements Callable<BuyerOutcome> {

    /**
     * Busy retries per buyer, hold and checkout together. Each wait is 1-2 s (1.5 s on average), so 30 retries are about
     * 45 s of a service refusing this buyer without a break: longer than a whole 10,000-buyer run took (38-41 s). A
     * buyer who reaches it saw a service that stayed saturated, which is a real failure, not a burst.
     */
    static final int MAX_BUSY_RETRIES = 30;
    /** Random extra wait added to {@code Retry-After} on a busy answer, 0 to this many ms. */
    static final int BUSY_JITTER_MS = 1000;
    /**
     * How long after the tokens are minted a buyer may still start a busy retry: their 15-minute lifetime minus a
     * minute, so a retried request never goes out with an expired token (it would be a 401, i.e. a NOT VALID run).
     */
    static final Duration RUN_DEADLINE = Duration.ofMinutes(14);
    /** The clock {@code deadlineMillis} and the step latencies are on: monotonic milliseconds. */
    static final LongSupplier MONOTONIC_MILLIS = () -> System.nanoTime() / 1_000_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RATE_LIMIT_WAITS = 30;
    private static final String BUSY_TYPE_SUFFIX = "/service-busy";

    private final String bearer;
    private final String idempotencyKey;
    private final long eventId;
    private final List<Long> seatIds;
    private final BookingApi api;
    private final CountDownLatch startGate;
    private final double hotShare;
    private final int maxAttempts;
    private final RandomGenerator rnd;
    private final Sleeper sleeper;
    private final LongSupplier clockMillis;
    private final long deadlineMillis;

    /** Sleeps for rate-limit and busy back-off; a seam so tests need not wait in real time. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * {@code idempotencyKey} is sent on the checkout; it must be unique per run (see LoadTestMain).
     * {@code deadlineMillis} is on {@link #MONOTONIC_MILLIS}: no busy retry may end after it.
     */
    public Buyer(String bearer, String idempotencyKey, long eventId, List<Long> seatIds, BookingApi api,
                 CountDownLatch startGate, double hotShare, int maxAttempts, RandomGenerator rnd, long deadlineMillis) {
        this(bearer, idempotencyKey, eventId, seatIds, api, startGate, hotShare, maxAttempts, rnd,
            Thread::sleep, MONOTONIC_MILLIS, deadlineMillis);
    }

    /** For tests: the deadline is {@link #RUN_DEADLINE} from now. */
    Buyer(String bearer, String idempotencyKey, long eventId, List<Long> seatIds, BookingApi api,
          CountDownLatch startGate, double hotShare, int maxAttempts, RandomGenerator rnd, Sleeper sleeper) {
        this(bearer, idempotencyKey, eventId, seatIds, api, startGate, hotShare, maxAttempts, rnd, sleeper,
            MONOTONIC_MILLIS, MONOTONIC_MILLIS.getAsLong() + RUN_DEADLINE.toMillis());
    }

    Buyer(String bearer, String idempotencyKey, long eventId, List<Long> seatIds, BookingApi api,
          CountDownLatch startGate, double hotShare, int maxAttempts, RandomGenerator rnd, Sleeper sleeper,
          LongSupplier clockMillis, long deadlineMillis) {
        this.sleeper = sleeper;
        this.bearer = bearer;
        this.idempotencyKey = idempotencyKey;
        this.eventId = eventId;
        this.seatIds = seatIds;
        this.api = api;
        this.startGate = startGate;
        this.hotShare = hotShare;
        this.maxAttempts = maxAttempts;
        this.rnd = rnd;
        this.clockMillis = clockMillis;
        this.deadlineMillis = deadlineMillis;
    }

    @Override
    public BuyerOutcome call() throws InterruptedException {
        startGate.await();
        int conflicts = 0;
        int rateLimited = 0;
        int busyRetries = 0;
        int errors = 0;
        int unauthorized = 0;
        List<Long> holdLat = new ArrayList<>();
        List<Long> checkoutLat = new ArrayList<>();
        try {
            String holdId = null;
            int attempts = 0;
            long seat = 0;
            long stepStart = 0;
            boolean sameSeatAgain = false;
            while (holdId == null && attempts < maxAttempts) {
                if (!sameSeatAgain) {
                    seat = SeatPicker.pick(seatIds, hotShare, rnd);
                    stepStart = clockMillis.getAsLong();
                }
                sameSeatAgain = false;
                HttpResponse<String> r = api.hold(bearer, eventId, seat);
                if (isBusy(r)) {
                    long wait = busyWaitMillis(r, busyRetries);
                    if (wait < 0) {
                        holdLat.add(clockMillis.getAsLong() - stepStart);
                        errors++;
                        break;
                    }
                    busyRetries++;
                    sleeper.sleep(wait);
                    sameSeatAgain = true;
                    continue;
                }
                // One figure per hold attempt, from its first send to its final answer, busy waits included.
                holdLat.add(clockMillis.getAsLong() - stepStart);
                int status = r.statusCode();
                if (status == 201) {
                    holdId = JSON.readTree(r.body()).get("holdId").asString();
                } else if (status == 429) {
                    if (++rateLimited > MAX_RATE_LIMIT_WAITS) {
                        errors++;
                        break;
                    }
                    sleeper.sleep(retryAfterSeconds(r) * 1000L);
                } else if (status == 401 || status == 403) {
                    unauthorized++;
                    break;
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
                return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, errors, unauthorized, holdLat,
                    checkoutLat);
            }
            HttpResponse<String> order;
            long checkoutStart = clockMillis.getAsLong();
            while (true) {
                order = api.checkout(bearer, idempotencyKey, holdId);
                if (!isBusy(order)) {
                    break;
                }
                long wait = busyWaitMillis(order, busyRetries);
                if (wait < 0) {
                    checkoutLat.add(clockMillis.getAsLong() - checkoutStart);
                    errors++;
                    return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, errors, unauthorized,
                        holdLat, checkoutLat);
                }
                busyRetries++;
                sleeper.sleep(wait);
            }
            // One figure for the checkout, from its first send to its final answer, busy waits included.
            checkoutLat.add(clockMillis.getAsLong() - checkoutStart);
            if (order.statusCode() == 201) {
                return new BuyerOutcome(true, conflicts, rateLimited, busyRetries, errors, unauthorized, holdLat,
                    checkoutLat);
            }
            if (order.statusCode() == 401 || order.statusCode() == 403) {
                unauthorized++;
            } else {
                // Including a 409: P2-R25 excuses only sold-out conflicts, and the seats this buyer holds cannot be
                // sold out. A checkout conflict means the system lost the hold (expired, taken over), so it is an error.
                errors++;
            }
        } catch (RuntimeException e) {
            errors++;
        }
        return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, errors, unauthorized, holdLat,
            checkoutLat);
    }

    /** 503 with problem type {@code service-busy}: the gateway refused the call before the service saw it. */
    private static boolean isBusy(HttpResponse<String> r) {
        return r.statusCode() == 503 && problemType(r).endsWith(BUSY_TYPE_SUFFIX);
    }

    /**
     * How long to wait before retrying a busy answer: {@code Retry-After} plus jitter. -1 when the buyer must give up
     * instead: it has used its {@link #MAX_BUSY_RETRIES}, or the retry would end after the run's deadline.
     */
    private long busyWaitMillis(HttpResponse<String> r, int retriesSoFar) {
        if (retriesSoFar >= MAX_BUSY_RETRIES) {
            return -1;
        }
        long wait = retryAfterSeconds(r) * 1000L + rnd.nextInt(BUSY_JITTER_MS + 1);
        return clockMillis.getAsLong() + wait > deadlineMillis ? -1 : wait;
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
