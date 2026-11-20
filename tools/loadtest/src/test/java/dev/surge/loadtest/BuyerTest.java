package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.loadtest.StubServer.Reply;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuyerTest {

    private static final String HOLDS = "POST /api/bookings/holds";
    private static final String ORDERS = "POST /api/bookings/orders";
    private static final Reply HOLD_OK = Reply.of(201, "{\"holdId\":\"h-1\",\"eventId\":1,\"seatIds\":[1]}");
    private static final Reply ORDER_OK = Reply.of(201, "{\"id\":1}");

    private StubServer stub;
    private BookingApi api;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubServer();
        api = new BookingApi(stub.baseUrl());
    }

    @AfterEach
    void tearDown() {
        api.close();
        stub.close();
    }

    private BuyerOutcome run() throws Exception {
        CountDownLatch gate = new CountDownLatch(0);
        return new Buyer("tok-0", "run-1-lt-0", 1, List.of(1L, 2L, 3L, 4L, 5L), api, gate, 0.7, 10,
            new Random(1), sleeps::add).call();
    }

    private static Reply problem(int status, String suffix) {
        return Reply.of(status, "{\"type\":\"https://surge.dev/problems/" + suffix + "\"}");
    }

    @Test
    void seatTakenThenSuccess() throws Exception {
        stub.on(HOLDS, problem(409, "seat-taken"), HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isTrue();
        assertThat(o.conflicts()).isEqualTo(1);
        assertThat(o.errors()).isZero();
        assertThat(o.holdLatenciesMs()).hasSize(2);
        assertThat(o.checkoutLatenciesMs()).hasSize(1);
        assertThat(stub.receivedBodies).anyMatch(b -> b.contains("\"holdId\":\"h-1\""));
        int checkout = stub.received.indexOf(ORDERS);
        assertThat(stub.receivedIdempotencyKeys.get(checkout)).isEqualTo("run-1-lt-0");
    }

    @Test
    void holdExistsStops() throws Exception {
        stub.on(HOLDS, problem(409, "hold-exists"));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.conflicts()).isEqualTo(1);
        assertThat(stub.received).containsExactly(HOLDS);
    }

    @Test
    void rateLimitedSleepsRetryAfterThenSucceeds() throws Exception {
        stub.on(HOLDS, new Reply(429, "", Map.of("Retry-After", "1")), HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isTrue();
        assertThat(o.rateLimited()).isEqualTo(1);
        assertThat(sleeps).containsExactly(1000L);
    }

    /**
     * P2-R25 excuses only sold-out conflicts, and a seat the buyer holds cannot be sold out: a 409 on its checkout means
     * the system lost the hold, so it counts towards the 1 % error gate, whatever its problem type.
     */
    @Test
    void checkoutConflictIsAnError() throws Exception {
        stub.on(HOLDS, HOLD_OK).on(ORDERS, problem(409, "hold-expired"));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
        assertThat(o.conflicts()).isZero();
        assertThat(stub.received).containsExactly(HOLDS, ORDERS);
    }

    @Test
    void checkoutConflictIsAnErrorEvenWhenItSaysSeatTaken() throws Exception {
        stub.on(HOLDS, HOLD_OK).on(ORDERS, problem(409, "seat-taken"));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
        assertThat(o.conflicts()).isZero();
    }

    @Test
    void nonJsonConflictBodyIsAnError() throws Exception {
        stub.on(HOLDS, Reply.of(409, "<html>nope</html>"));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
        assertThat(o.conflicts()).isZero();
    }

    @Test
    void emptyConflictBodyIsAnError() throws Exception {
        stub.on(HOLDS, Reply.of(409, ""));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
    }

    @Test
    void networkFailureIsAnError() throws Exception {
        stub.close();
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
    }

    // ---- P2-R26: 503 service-busy (the gateway's full bulkhead) is retried, any other 503 is an error ----------

    private static final Reply BUSY = busy("1");

    private static Reply busy(String retryAfter) {
        return new Reply(503, "{\"type\":\"https://surge.dev/problems/service-busy\",\"status\":503}",
            Map.of("Retry-After", retryAfter));
    }

    /** Like {@link #run()} with a clock that stands still at 0 and the run's deadline {@code deadlineMs} later. */
    private BuyerOutcome runWithDeadlineIn(long deadlineMs) throws Exception {
        return new Buyer("tok-0", "run-1-lt-0", 1, List.of(1L, 2L, 3L, 4L, 5L), api, new CountDownLatch(0), 0.7, 10,
            new Random(1), sleeps::add, () -> 0L, deadlineMs).call();
    }

    @Test
    void busyHoldWaitsRetryAfterPlusJitterThenRetriesTheSameSeat() throws Exception {
        stub.on(HOLDS, BUSY, HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isTrue();
        assertThat(o.busyRetries()).isEqualTo(1);
        assertThat(o.errors()).isZero();
        assertThat(sleeps).singleElement().satisfies(ms -> assertThat(ms).isBetween(1000L, 2000L));
        assertThat(stub.received).containsExactly(HOLDS, HOLDS, ORDERS);
        // booking never saw the refused hold, so the same request goes again
        assertThat(stub.receivedBodies.get(1)).isEqualTo(stub.receivedBodies.get(0));
    }

    @Test
    void busyHonoursALongerRetryAfter() throws Exception {
        stub.on(HOLDS, busy("3"), HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isTrue();
        assertThat(sleeps).singleElement().satisfies(ms -> assertThat(ms).isBetween(3000L, 4000L));
    }

    @Test
    void busyCheckoutIsRetriedWithTheSameIdempotencyKey() throws Exception {
        stub.on(HOLDS, HOLD_OK).on(ORDERS, BUSY, BUSY, ORDER_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isTrue();
        assertThat(o.busyRetries()).isEqualTo(2);
        assertThat(o.errors()).isZero();
        assertThat(o.checkoutLatenciesMs()).hasSize(3);
        assertThat(stub.received).containsExactly(HOLDS, ORDERS, ORDERS, ORDERS);
        assertThat(stub.receivedIdempotencyKeys.subList(1, 4)).containsOnly("run-1-lt-0");
    }

    @Test
    void aBuyerStillBusyAfterTheRetryCapGivesUpWithAnError() throws Exception {
        stub.on(HOLDS, BUSY);
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.busyRetries()).isEqualTo(Buyer.MAX_BUSY_RETRIES);
        assertThat(o.errors()).isEqualTo(1);
        assertThat(sleeps).hasSize(Buyer.MAX_BUSY_RETRIES);
        assertThat(stub.received).hasSize(Buyer.MAX_BUSY_RETRIES + 1).containsOnly(HOLDS);
    }

    @Test
    void aBusyRetryThatWouldEndAfterTheDeadlineIsNotMadeAndIsAnError() throws Exception {
        stub.on(HOLDS, BUSY, HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = runWithDeadlineIn(500); // the shortest wait is Retry-After's 1 s
        assertThat(o.bought()).isFalse();
        assertThat(o.busyRetries()).isZero();
        assertThat(o.errors()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(stub.received).containsExactly(HOLDS);
    }

    @Test
    void aBusyRetryThatEndsBeforeTheDeadlineIsMade() throws Exception {
        stub.on(HOLDS, BUSY, HOLD_OK).on(ORDERS, ORDER_OK);
        BuyerOutcome o = runWithDeadlineIn(2000); // Retry-After 1 s plus at most 1 s of jitter
        assertThat(o.bought()).isTrue();
        assertThat(o.busyRetries()).isEqualTo(1);
    }

    @Test
    void anyOther503IsStillAnErrorAndNotRetried() throws Exception {
        stub.on(HOLDS, new Reply(503, "{\"type\":\"https://surge.dev/problems/service-unavailable\"}",
            Map.of("Retry-After", "5")), HOLD_OK);
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.errors()).isEqualTo(1);
        assertThat(o.busyRetries()).isZero();
        assertThat(sleeps).isEmpty();
        assertThat(stub.received).containsExactly(HOLDS);
    }

    @Test
    void sendsOnlyItsOwnBearerAndNeverXUserId() throws Exception {
        stub.on(HOLDS, HOLD_OK).on(ORDERS, ORDER_OK);
        run();
        assertThat(stub.receivedAuth).containsExactly("Bearer tok-0", "Bearer tok-0");
        assertThat(stub.receivedUserIdHeader).containsOnly(false);
    }
}
