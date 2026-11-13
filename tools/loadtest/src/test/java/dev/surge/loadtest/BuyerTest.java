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
        return new Buyer("lt-0", "run-1-lt-0", 1, List.of(1L, 2L, 3L, 4L, 5L), api, gate, 0.7, 10,
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

    @Test
    void checkoutConflictStops() throws Exception {
        stub.on(HOLDS, HOLD_OK).on(ORDERS, problem(409, "hold-expired"));
        BuyerOutcome o = run();
        assertThat(o.bought()).isFalse();
        assertThat(o.conflicts()).isEqualTo(1);
        assertThat(o.errors()).isZero();
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
}
