package dev.surge.notification.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain unit test: a temp directory and a settable clock, no Spring. */
class AuditLogWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private static final class SettableClock extends Clock {
        Instant now;

        SettableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private AuditLogWriter writer(SettableClock clock) {
        return new AuditLogWriter(dir.toString(), clock, JSON);
    }

    @Test
    void twoAppendsGiveTwoLinesInOneDailyFile() throws Exception {
        SettableClock clock = new SettableClock("2026-11-14T10:00:00Z");
        AuditLogWriter writer = writer(clock);

        writer.append("OrderConfirmed", "{\"messageId\": \"m-1\",  \"orderId\": 7}");
        clock.now = Instant.parse("2026-11-14T10:00:05Z");
        writer.append("SalesReset", "{\"eventId\":3}");

        Path file = dir.resolve("orders-2026-11-14.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        JsonNode first = JSON.readTree(lines.get(0));
        assertThat(first.get("at").asString()).isEqualTo("2026-11-14T10:00:00Z");
        assertThat(first.get("type").asString()).isEqualTo("OrderConfirmed");
        // the payload is a real JSON object, written compactly, not a quoted string
        assertThat(first.get("payload").isObject()).isTrue();
        assertThat(first.get("payload").get("orderId").asInt()).isEqualTo(7);
        assertThat(lines.get(0)).contains("\"payload\":{\"messageId\":\"m-1\",\"orderId\":7}");
        assertThat(JSON.readTree(lines.get(1)).get("type").asString()).isEqualTo("SalesReset");
    }

    @Test
    void fileRollsOverAtIstMidnight() throws Exception {
        SettableClock clock = new SettableClock("2026-11-14T18:29:59Z");
        AuditLogWriter writer = writer(clock);

        writer.append("A", "{}");
        clock.now = Instant.parse("2026-11-14T18:30:00Z");
        writer.append("B", "{}");

        assertThat(Files.readAllLines(dir.resolve("orders-2026-11-14.jsonl"))).hasSize(1);
        assertThat(Files.readAllLines(dir.resolve("orders-2026-11-15.jsonl"))).hasSize(1);
        assertThat(writer.today()).isEqualTo(LocalDate.parse("2026-11-15"));
    }

    @Test
    void createsTheDirectoryWhenMissing() throws Exception {
        Path nested = dir.resolve("a/b");
        AuditLogWriter writer = new AuditLogWriter(nested.toString(), new SettableClock("2026-11-14T10:00:00Z"), JSON);

        writer.append("A", "{}");

        assertThat(Files.readAllLines(nested.resolve("orders-2026-11-14.jsonl"))).hasSize(1);
    }

    @Test
    void invalidPayloadWritesNothing() {
        AuditLogWriter writer = writer(new SettableClock("2026-11-14T10:00:00Z"));

        assertThatThrownBy(() -> writer.append("A", "{not json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> writer.append("A", "[1,2]")).isInstanceOf(IllegalArgumentException.class);

        assertThat(dir.resolve("orders-2026-11-14.jsonl")).doesNotExist();
    }

    @Test
    void fiftyVirtualThreadsGiveFiftyWellFormedLines() throws Exception {
        AuditLogWriter writer = writer(new SettableClock("2026-11-14T10:00:00Z"));
        String padding = "x".repeat(20_000);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int n = i;
                futures.add(pool.submit(() -> writer.append("T" + n, "{\"n\":" + n + ",\"pad\":\"" + padding + "\"}")));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        List<String> lines = Files.readAllLines(dir.resolve("orders-2026-11-14.jsonl"));
        assertThat(lines).hasSize(50);
        assertThat(lines).allSatisfy(line -> assertThat(JSON.readTree(line).get("payload").get("pad").asString())
                .isEqualTo(padding));
        assertThat(lines.stream().map(l -> JSON.readTree(l).get("payload").get("n").asInt()).distinct().count())
                .isEqualTo(50);
    }

    @Test
    void moneyIsWrittenExactlyAsSent() throws Exception {
        AuditLogWriter writer = writer(new SettableClock("2026-11-14T10:00:00Z"));

        writer.append("OrderConfirmed", "{\"totalInr\":10.00,\"a\":12345678.90,\"b\":123456789012345678.25,\"c\":1500.00}");

        String line = Files.readAllLines(dir.resolve("orders-2026-11-14.jsonl")).getFirst();
        assertThat(line).contains("\"payload\":{\"totalInr\":10.00,\"a\":12345678.90,\"b\":123456789012345678.25,"
                + "\"c\":1500.00}");
        assertThat(writer.tail(LocalDate.parse("2026-11-14"), 1).tail().getFirst()).isEqualTo(line);
    }

    @Test
    void aFailedAppendLeavesNoFragmentForTheRetryToMerge() throws Exception {
        SettableClock clock = new SettableClock("2026-11-14T10:00:00Z");
        boolean[] failNext = {false};
        AuditLogWriter writer = new AuditLogWriter(dir.toString(), clock, JSON) {
            @Override
            void write(Path file, String text) throws java.io.IOException {
                if (failNext[0]) {
                    failNext[0] = false;
                    Files.writeString(file, text.substring(0, 10), java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    throw new java.io.IOException("disk full");
                }
                super.write(file, text);
            }
        };
        writer.append("A", "{\"n\":1}");
        writer.append("B", "{\"n\":2}");
        failNext[0] = true;
        assertThatThrownBy(() -> writer.append("C", "{\"n\":3}")).isInstanceOf(java.io.UncheckedIOException.class);
        writer.append("C", "{\"n\":3}");

        List<String> lines = Files.readAllLines(dir.resolve("orders-2026-11-14.jsonl"));
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(l -> assertThat(JSON.readTree(l).get("type")).isNotNull());
    }

    @Test
    void tailSurvivesMalformedUtf8() throws Exception {
        AuditLogWriter writer = writer(new SettableClock("2026-11-14T10:00:00Z"));
        writer.append("A", "{}");
        Files.write(dir.resolve("orders-2026-11-14.jsonl"), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 10},
                java.nio.file.StandardOpenOption.APPEND);

        AuditLogWriter.Tail tail = writer.tail(LocalDate.parse("2026-11-14"), 5);

        assertThat(tail.lines()).isEqualTo(2);
    }

    @Test
    void tailReturnsTheLastLinesAndTheTotal() {
        AuditLogWriter writer = writer(new SettableClock("2026-11-14T10:00:00Z"));
        for (int i = 1; i <= 5; i++) {
            writer.append("T", "{\"n\":" + i + "}");
        }

        AuditLogWriter.Tail tail = writer.tail(LocalDate.parse("2026-11-14"), 2);

        assertThat(tail.lines()).isEqualTo(5);
        assertThat(tail.tail()).hasSize(2);
        assertThat(JSON.readTree(tail.tail().get(0)).get("payload").get("n").asInt()).isEqualTo(4);
        assertThat(JSON.readTree(tail.tail().get(1)).get("payload").get("n").asInt()).isEqualTo(5);
        assertThat(writer.tail(LocalDate.parse("2026-11-14"), 500).tail()).hasSize(5);
    }

    @Test
    void tailOfAMissingDayIsEmpty() {
        AuditLogWriter.Tail tail = writer(new SettableClock("2026-11-14T10:00:00Z")).tail(LocalDate.parse("2020-01-01"), 50);

        assertThat(tail.lines()).isZero();
        assertThat(tail.tail()).isEmpty();
    }
}
