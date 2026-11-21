package dev.surge.loadtest;

import java.io.UncheckedIOException;
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
 * an error at once on a HOLD.
 *
 * <p>A CHECKOUT is also sent again, unchanged (same {@code Idempotency-Key}, so booking replays the order if the first
 * try went through), up to {@value #MAX_CHECKOUT_RETRIES} times and within the run's deadline, after a timeout or
 * connection failure, 502, 504, a 503 that is not service-busy (such as auth-unavailable or payment-unavailable) and a
 * 409 request-in-progress. It waits {@code Retry-After} when the answer has one, else 1-2 s. A 200 with
 * {@code Idempotent-Replayed: true} is a success like a 201. A hold is never sent again blindly; a 409 hold-exists whose
 * problem body carries the existing {@code holdId} is adopted and checked out.
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
     * Checkout retries per buyer, with the same Idempotency-Key, after a timeout, 502, 504, a 503 that is not
     * service-busy (e.g. auth-unavailable, payment-unavailable) or a 409 request-in-progress. Separate from the busy
     * retries; each also stays within the run deadline.
     */
    static final int MAX_CHECKOUT_RETRIES = 3;
    /** Booking's header on a replayed checkout (HTTP 200): the order of this Idempotency-Key already existed. */
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";
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
        int checkoutRetries = 0;
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
                    // The problem body carries the buyer's existing hold (e.g. a hold request whose answer was lost):
                    // adopt it and go on to the checkout. Without it there is nothing to adopt; a hold is never
                    // sent again blindly.
                    holdId = existingHoldId(r);
                    if (holdId == null) {
                        break;
                    }
                } else {
                    errors++;
                    break;
                }
            }
            if (holdId == null) {
                return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, checkoutRetries, errors, unauthorized, holdLat,
                    checkoutLat);
            }
            HttpResponse<String> order;
            long checkoutStart = clockMillis.getAsLong();
            while (true) {
                try {
                    order = api.checkout(bearer, idempotencyKey, holdId);
                } catch (UncheckedIOException e) {
                    order = null; // timeout or connection failure: the order may or may not exist, ask again
                }
                if (order != null && isBusy(order)) {
                    long wait = busyWaitMillis(order, busyRetries);
                    if (wait < 0) {
                        checkoutLat.add(clockMillis.getAsLong() - checkoutStart);
                        errors++;
                        return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, checkoutRetries, errors,
                            unauthorized, holdLat, checkoutLat);
                    }
                    busyRetries++;
                    sleeper.sleep(wait);
                    continue;
                }
                if (order != null && !retryableCheckout(order)) {
                    break;
                }
                // Same Idempotency-Key, so a retry of a checkout that did go through replays the order (200).
                long wait = checkoutRetryWaitMillis(order, checkoutRetries);
                if (wait < 0) {
                    break;
                }
                checkoutRetries++;
                sleeper.sleep(wait);
            }
            // One figure for the checkout, from its first send to its final answer, waits and retries included.
            checkoutLat.add(clockMillis.getAsLong() - checkoutStart);
            if (order != null && (order.statusCode() == 201 || isReplay(order))) {
                return new BuyerOutcome(true, conflicts, rateLimited, busyRetries, checkoutRetries, errors,
                    unauthorized, holdLat, checkoutLat);
            }
            if (order != null && (order.statusCode() == 401 || order.statusCode() == 403)) {
                unauthorized++;
            } else {
                // Including a 409: P2-R25 excuses only sold-out conflicts, and the seats this buyer holds cannot be
                // sold out. A checkout conflict means the system lost the hold (expired, taken over), so it is an error.
                errors++;
            }
        } catch (RuntimeException e) {
            errors++;
        }
        return new BuyerOutcome(false, conflicts, rateLimited, busyRetries, checkoutRetries, errors, unauthorized, holdLat,
            checkoutLat);
    }

    /** 200 with {@code Idempotent-Replayed: true}: booking found the order of this Idempotency-Key and returned it. */
    private static boolean isReplay(HttpResponse<String> r) {
        return r.statusCode() == 200 && "true".equalsIgnoreCase(r.headers().firstValue(REPLAYED_HEADER).orElse(""));
    }

    /**
     * A checkout answer worth sending again with the same key: 502, 504, any 503 that is not service-busy (that one has
     * its own retry path), a 503 {@code auth-unavailable} among them, and 409 {@code request-in-progress} (the first
     * attempt is still running).
     */
    private static boolean retryableCheckout(HttpResponse<String> r) {
        int status = r.statusCode();
        return status == 502 || status == 504 || (status == 503 && !isBusy(r))
            || (status == 409 && problemType(r).endsWith("/request-in-progress"));
    }

    /**
     * How long before the next checkout retry: {@code Retry-After} if the answer has one, else 1-2 s (1 s plus jitter).
     * -1 when the buyer must give up: {@link #MAX_CHECKOUT_RETRIES} used, or the retry would end after the deadline.
     * {@code last} is null after a timeout or connection failure.
     */
    private long checkoutRetryWaitMillis(HttpResponse<String> last, int retriesSoFar) {
        if (retriesSoFar >= MAX_CHECKOUT_RETRIES) {
            return -1;
        }
        boolean hasRetryAfter = last != null && last.headers().firstValue("Retry-After").isPresent();
        long wait = hasRetryAfter ? retryAfterSeconds(last) * 1000L : 1000L + rnd.nextInt(BUSY_JITTER_MS + 1);
        return clockMillis.getAsLong() + wait > deadlineMillis ? -1 : wait;
    }

    /** The {@code holdId} of a 409 hold-exists problem, or null when the body does not carry one. */
    private static String existingHoldId(HttpResponse<String> r) {
        try {
            JsonNode id = JSON.readTree(r.body()).get("holdId");
            return id == null || id.asString().isBlank() ? null : id.asString();
        } catch (RuntimeException e) {
            return null;
        }
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
