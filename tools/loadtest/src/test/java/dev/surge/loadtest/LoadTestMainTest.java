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
        String seats = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> "{\"id\":" + i + ",\"rowLabel\":\"A\",\"number\":" + i + "}")
            .collect(Collectors.joining(","));
        return new StubServer()
            .on("POST /api/bookings/admin/events/1/reset", Reply.of(204, ""))
            .on("GET /api/bookings/events/1", Reply.of(200, "{\"id\":1,\"seats\":[" + seats + "]}"))
            .on("POST /api/bookings/holds", Reply.of(201, "{\"holdId\":\"h-1\"}"))
            .on(ORDERS, Reply.of(201, "{\"id\":1}"))
            .on("GET /api/bookings/admin/events/1/integrity", Reply.of(200,
                "{\"eventId\":1,\"totalSeats\":10,\"soldSeats\":" + soldSeats + ",\"orderItems\":3,"
                    + "\"distinctSeatsSold\":3,\"oversold\":" + oversold + ",\"consistent\":" + consistent + "}"))
            .on(PUT_REPORT, Reply.of(204, ""));
    }

    private static LoadTestConfig config(StubServer s, Path out) {
        return LoadTestConfig.parse(new String[] {
            "--users", "3", "--base-url", s.baseUrl().toString(), "--out", out.toString()});
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
                assertThat(k).matches("[0-9a-f-]{36}-lt-[0-2]").hasSizeLessThanOrEqualTo(64);
                prefixes.add(k.substring(0, 36));
            }
            assertThat(prefixes).hasSize(2);
            assertThat(second).doesNotContainAnyElementsOf(first);
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
