package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.loadtest.StubServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadTestMainTest {

    private static final String ORDERS = "POST /api/bookings/orders";
    private static final String PUT_REPORT = "PUT /api/bookings/admin/loadtest-reports/latest";

    /** Three buyers all get a 201; the integrity endpoint answers with the given numbers. */
    private static StubServer stub(int soldSeats, int oversold, boolean consistent) throws Exception {
        return stub(0, soldSeats, oversold, consistent);
    }

    private static String integrity(int soldSeats, int oversold, boolean consistent, int seatMismatches) {
        return "{\"eventId\":1,\"totalSeats\":10,\"soldSeats\":" + soldSeats + ",\"orderItems\":" + soldSeats + ","
            + "\"distinctSeatsSold\":" + soldSeats + ",\"oversold\":" + oversold + ",\"seatMismatches\":"
            + seatMismatches + ",\"consistent\":" + consistent + "}";
    }

    /**
     * Like {@link #stub(int, int, boolean)}, with {@code soldBefore} seats already SOLD when the run starts: the first
     * integrity read (before the buyers are released) answers that, every later one the numbers after the run.
     */
    private static StubServer stub(int soldBefore, int soldSeats, int oversold, boolean consistent) throws Exception {
        String seats = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "{\"id\":" + i + ",\"rowLabel\":\"A\",\"number\":" + i + "}")
            .collect(Collectors.joining(","));
        return new StubServer()
            .on("POST /api/auth/login", Reply.of(200, "{\"token\":\"admin-jwt\"}"))
            .onBody("POST /api/auth/admin/test-tokens", GatewayAuthTest::mintReply)
            .on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""))
            .on("GET /api/bookings/events/1", Reply.of(200, "{\"id\":1,\"seats\":[" + seats + "]}"))
            .on("POST /api/bookings/holds", Reply.of(201, "{\"holdId\":\"h-1\"}"))
            .on(ORDERS, Reply.of(201, "{\"id\":1}"))
            .on("GET /api/bookings/admin/events/1/integrity",
                Reply.of(200, integrity(soldBefore, 0, true, 0)),
                Reply.of(200, integrity(soldSeats, oversold, consistent, consistent ? 0 : 1)))
            .on(PUT_REPORT, Reply.of(204, ""));
    }

    private static LoadTestConfig config(StubServer s, Path out) {
        return LoadTestConfig.parse(new String[] {
            "--users", "3", "--base-url", s.baseUrl().toString(), "--out", out.toString(),
            "--admin-password", "pw"});
    }

    private static List<String> checkoutKeys(StubServer s) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < s.received.size(); i++) {
            if (s.received.get(i).equals(ORDERS)) {
                keys.add(s.receivedIdempotencyKeys.get(i));
            }
        }
        return keys;
    }

    @Test
    void passingRunExitsZeroAndWritesReport(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 0, true)) {
            assertThat(LoadTestMain.run(config(s, out))).isZero();

            assertThat(s.received).contains("POST /api/bookings/admin/events/1/reset", PUT_REPORT);
            String body = s.receivedBodies.get(s.received.indexOf(PUT_REPORT));
            assertThat(body).contains("\"oversold\" : 0").contains("\"consistent\" : true")
                .contains("\"buyersSucceeded\" : 3").contains("\"requests\" : 6");
            try (Stream<Path> files = Files.list(out)) {
                assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(n -> n.endsWith(".json")).anyMatch(n -> n.endsWith(".md"));
            }
        }
    }

    /**
     * Each buyer's checkout is refused busy once (the queue's three busy answers, then 201s): the run passes, reports
     * the 3 busy retries, and its request count still counts every request sent (3 holds + 3 checkouts + 3 retries),
     * although each retried checkout is one latency figure.
     */
    @Test
    void busyRetriesAreReportedAndCountedAsRequests(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 0, true)) {
            Reply busy = new Reply(503, "{\"type\":\"https://surge.dev/problems/service-busy\"}",
                java.util.Map.of("Retry-After", "1"));
            s.on(ORDERS, busy, busy, busy, Reply.of(201, "{\"id\":1}"));

            assertThat(LoadTestMain.run(config(s, out))).isZero();

            String body = s.receivedBodies.get(s.received.indexOf(PUT_REPORT));
            assertThat(body).containsPattern("\"busyRetries\"\\s*:\\s*3").containsPattern("\"requests\"\\s*:\\s*9")
                .containsPattern("\"errors\"\\s*:\\s*0");
        }
    }

    @Test
    void idempotencyKeysAreUniquePerRun(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 0, true)) {
            LoadTestMain.run(config(s, out));
            List<String> first = checkoutKeys(s);
            LoadTestMain.run(config(s, out));
            List<String> both = checkoutKeys(s);
            List<String> second = both.subList(first.size(), both.size());

            assertThat(first).hasSize(3).doesNotHaveDuplicates();
            assertThat(second).hasSize(3).doesNotHaveDuplicates();
            // runId + "-" + userId: one shared prefix per run, a different one for the next run.
            Set<String> prefixes = new HashSet<>();
            for (String k : both) {
                assertThat(k).matches("[0-9a-f-]{36}-lt-[0-9a-f]{8}-[0-2]").hasSizeLessThanOrEqualTo(64);
                prefixes.add(k.substring(0, 36));
            }
            assertThat(prefixes).hasSize(2);
            assertThat(second).doesNotContainAnyElementsOf(first);
            assertThat(both).allSatisfy(k -> assertThat(k).matches(SERVER_KEY_RULE));
        }
    }

    /** booking-service's Idempotency-Key rule (OrderController, SEC-25): anything else is 400 validation-failed. */
    private static final String SERVER_KEY_RULE = "^[A-Za-z0-9_-]{8,64}$";

    /**
     * The longest key the tester can send: a UUID run id, "-", and the longest minted user id (a 19-character batch
     * prefix, "-", and an index below MINT_BATCH, at most 4 digits). It must still pass booking's rule.
     */
    @Test
    void theLongestIdempotencyKeyPassesTheServersRule() {
        String longestUser = "lt-" + "f".repeat(8) + "b" + "9".repeat(7) + "-" + (BookingApi.MINT_BATCH - 1);
        assertThat(longestUser).hasSize(24);
        String key = LoadTestMain.idempotencyKey(java.util.UUID.randomUUID().toString(), longestUser);
        assertThat(key).hasSize(61).matches(SERVER_KEY_RULE);
        // and the shortest: a one-digit index on the plain prefix
        assertThat(LoadTestMain.idempotencyKey(java.util.UUID.randomUUID().toString(), "lt-0a1b2c3d-0"))
                .matches(SERVER_KEY_RULE);
    }

    @Test
    void aRunWhereEveryHoldFailedExitsOneInsteadOfPassing(@TempDir Path out) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (StubServer s = stub(0, 0, true);
             PrintStream o = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            s.on("POST /api/bookings/holds", Reply.of(503, "{\"type\":\"https://surge.dev/problems/service-unavailable\"}"));

            assertThat(LoadTestMain.run(config(s, out), o, e)).isEqualTo(1);
            assertThat(s.received).doesNotContain(ORDERS);
        }
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("**FAIL ❌**").doesNotContain("PASS");
        assertThat(stderr.toString(StandardCharsets.UTF_8))
            .contains("ERROR: nothing was sold although the event has 10 seats")
            .contains("ERROR: 3 of 3 buyers ended in an error (more than 1 %)");
    }

    /** I2: --no-reset on an event with earlier sales: the 3 buyers' sales are the delta, not all 8 SOLD seats. */
    @Test
    void noResetOnAnEventWithEarlierSalesPassesOnTheDelta(@TempDir Path out) throws Exception {
        try (StubServer s = stub(5, 8, 0, true)) {
            LoadTestConfig cfg = LoadTestConfig.parse(new String[] {
                "--users", "3", "--no-reset", "--base-url", s.baseUrl().toString(), "--out", out.toString(),
                "--admin-password", "pw"});

            assertThat(LoadTestMain.run(cfg)).isZero();

            assertThat(s.received).doesNotContain("POST /api/bookings/admin/events/1/reset");
            String body = s.receivedBodies.get(s.received.indexOf(PUT_REPORT));
            assertThat(body).containsPattern("\"sold\"\\s*:\\s*8").containsPattern("\"soldBefore\"\\s*:\\s*5")
                .containsPattern("\"soldInRun\"\\s*:\\s*3").containsPattern("\"passed\"\\s*:\\s*true")
                .containsPattern("\"seatMismatches\"\\s*:\\s*0").containsPattern("\"checkoutRetries\"\\s*:\\s*0");
        }
    }

    @Test
    void noResetStillFailsWhenTheDeltaIsNotWhatTheBuyersBought(@TempDir Path out) throws Exception {
        try (StubServer s = stub(5, 9, 0, true)) { // 4 new SOLD seats, but only 3 buyers got a 201
            LoadTestConfig cfg = LoadTestConfig.parse(new String[] {
                "--users", "3", "--no-reset", "--base-url", s.baseUrl().toString(), "--out", out.toString(),
                "--admin-password", "pw"});

            assertThat(LoadTestMain.run(cfg)).isEqualTo(1);
        }
    }

    @Test
    void theIntegrityBaselineIsReadBeforeTheBuyersStart(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 0, true)) {
            LoadTestMain.run(config(s, out));

            int first = s.received.indexOf("GET /api/bookings/admin/events/1/integrity");
            assertThat(first).isLessThan(s.received.indexOf("POST /api/bookings/holds"));
            assertThat(s.received.lastIndexOf("GET /api/bookings/admin/events/1/integrity"))
                .isGreaterThan(s.received.lastIndexOf(ORDERS));
        }
    }

    @Test
    void oversoldExitsOne(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 1, true)) {
            assertThat(LoadTestMain.run(config(s, out))).isEqualTo(1);
        }
    }

    @Test
    void inconsistentSoldSeatsExitOne(@TempDir Path out) throws Exception {
        try (StubServer s = stub(3, 0, false)) {
            assertThat(LoadTestMain.run(config(s, out))).isEqualTo(1);
        }
    }

    @Test
    void moreSeatsSoldThanBuyersWhoSucceededExitsOne(@TempDir Path out) throws Exception {
        try (StubServer s = stub(4, 0, true)) {
            assertThat(LoadTestMain.run(config(s, out))).isEqualTo(1);
        }
    }

    @Test
    void unwritableReportFolderIsNotFatal(@TempDir Path tmp) throws Exception {
        Path notADirectory = Files.createFile(tmp.resolve("reports"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (StubServer s = stub(3, 0, true);
             PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int exit = LoadTestMain.run(config(s, notADirectory), out, err);

            assertThat(exit).isZero();
            assertThat(s.received).contains(PUT_REPORT); // published before the failed write
        }
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("# Surge load test").contains("PASS");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("WARN: could not write the report files")
            .contains("chmod o+w reports");
    }
}
