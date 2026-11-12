package dev.surge.loadtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

public final class ReportWriter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(IST);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ReportWriter() {
    }

    public static String json(LoadTestReport r) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r);
    }

    public static List<Path> write(Path dir, LoadTestReport r) {
        String base = "loadtest-" + STAMP.format(r.startedAt());
        Path json = dir.resolve(base + ".json");
        Path md = dir.resolve(base + ".md");
        try {
            Files.createDirectories(dir);
            Files.writeString(json, json(r));
            Files.writeString(md, markdown(r));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of(json, md);
    }

    public static String markdown(LoadTestReport r) {
        String verdict = r.oversold() == 0 ? "Oversold: 0 ✅" : "Oversold: " + r.oversold() + " ❌";
        return """
            # Surge load test

            Started: %s (Asia/Kolkata) · Event %d · %d buyers · %d ms

            **%s**

            | Metric | Value |
            |---|---|
            | Total seats | %d |
            | Sold | %d |
            | Buyers succeeded | %d |
            | Conflicts | %d |
            | Rate limited | %d |
            | Errors | %d |

            | Latency (ms) | p50 | p95 | p99 | max |
            |---|---|---|---|---|
            | Hold | %d | %d | %d | %d |
            | Checkout | %d | %d | %d | %d |
            """.formatted(
                HUMAN.format(r.startedAt()),
                r.eventId(), r.users(), r.durationMs(), verdict,
                r.totalSeats(), r.sold(), r.buyersSucceeded(), r.conflicts(), r.rateLimited(), r.errors(),
                r.hold().p50(), r.hold().p95(), r.hold().p99(), r.hold().max(),
                r.checkout().p50(), r.checkout().p95(), r.checkout().p99(), r.checkout().max());
    }
}
