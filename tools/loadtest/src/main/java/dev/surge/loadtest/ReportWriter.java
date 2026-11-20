package dev.surge.loadtest;

import java.io.IOException;
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
    private static final String OK = "✅";
    private static final String BAD = "❌";

    private ReportWriter() {
    }

    public static String json(LoadTestReport r) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r);
    }

    /** Writes {@code loadtest-<IST timestamp>.json} and {@code .md} into {@code dir}, creating it if needed. */
    public static List<Path> write(Path dir, LoadTestReport r) throws IOException {
        String base = "loadtest-" + STAMP.format(r.startedAt());
        Path json = dir.resolve(base + ".json");
        Path md = dir.resolve(base + ".md");
        Files.createDirectories(dir);
        Files.writeString(json, json(r));
        Files.writeString(md, markdown(r));
        return List.of(json, md);
    }

    public static String markdown(LoadTestReport r) {
        String verdict = r.passed() ? "PASS " + OK : "FAIL " + BAD;
        if (r.unauthorized() > 0) {
            // Rejected buyers make the run not valid. Only a broken invariant is still reported as a FAIL; nothing sold
            // or many errors are expected consequences of the rejection.
            String note = "NOT VALID: " + r.unauthorized() + " buyer tokens rejected";
            verdict = r.invariantsHold() ? note : verdict + " (" + note + ")";
        }
        return """
            # Surge load test

            Started: %s (Asia/Kolkata) · Event %d · %d buyers · %d ms

            **%s**

            | Check | Result |
            |---|---|
            | Oversold (a seat in two order lines) | %d %s |
            | Seats marked SOLD = distinct seats in orders | %s %s |
            | Buyers who got a 201 = seats sold | %d = %d %s |
            | Seats sold (at least one) | %d %s |
            | Buyers ending in an error (at most %d %%) | %d of %d %s |

            | Metric | Value |
            |---|---|
            | Total seats | %d |
            | Sold | %d |
            | Buyers succeeded | %d |
            | Requests | %d |
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
                r.oversold(), mark(r.oversold() == 0),
                r.consistent() ? "yes" : "no", mark(r.consistent()),
                r.buyersSucceeded(), r.sold(), mark(r.buyersSucceeded() == r.sold()),
                r.sold(), mark(!r.nothingSold()),
                LoadTestReport.MAX_ERROR_PERCENT, r.errors(), r.users(), mark(!r.tooManyErrors()),
                r.totalSeats(), r.sold(), r.buyersSucceeded(), r.requests(), r.conflicts(), r.rateLimited(), r.errors(),
                r.hold().p50(), r.hold().p95(), r.hold().p99(), r.hold().max(),
                r.checkout().p50(), r.checkout().p95(), r.checkout().p99(), r.checkout().max());
    }

    private static String mark(boolean ok) {
        return ok ? OK : BAD;
    }
}
