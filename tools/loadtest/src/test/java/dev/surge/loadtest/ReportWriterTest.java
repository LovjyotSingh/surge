package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    private static final LatencyStats ZERO = new LatencyStats(0, 0, 0, 0);

    private static LoadTestReport report(int sold, int oversold, boolean consistent, int succeeded) {
        return new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), 10, 1, 500, sold, oversold, consistent,
            succeeded, 2, 0, 0, 0, 1, ZERO, ZERO, 0);
    }

    @Test
    void writesJsonAndMarkdown(@TempDir Path dir) throws Exception {
        var r = new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), 10000, 1, 500, 500, 0, true,
            500, 640, 321, 7, 0, 4200, new LatencyStats(5, 20, 40, 90), new LatencyStats(8, 25, 50, 120), 0);

        List<Path> files = ReportWriter.write(dir, r);

        assertThat(files).extracting(p -> p.getFileName().toString())
            .containsExactly("loadtest-20261112-120000.json", "loadtest-20261112-120000.md");
        String md = Files.readString(dir.resolve("loadtest-20261112-120000.md"));
        assertThat(md).contains("**PASS ✅**")
            .contains("| Oversold (a seat in two order lines) | 0 ✅ |")
            .contains("| Seats marked SOLD = distinct seats in orders | yes ✅ |")
            .contains("| Buyers who got a 201 = seats sold | 500 = 500 ✅ |")
            .contains("| Sold | 500 |").contains("| Requests | 640 |");
        assertThat(Files.readString(dir.resolve("loadtest-20261112-120000.json")))
            .contains("\"oversold\"").contains("\"consistent\"").contains("\"sold\"").contains("2026-11-12T06:30:00Z");
    }

    @Test
    void marksOversoldAsFailure() {
        String md = ReportWriter.markdown(report(501, 1, true, 501));
        assertThat(md).contains("**FAIL ❌**").contains("| Oversold (a seat in two order lines) | 1 ❌ |");
    }

    @Test
    void marksInconsistentSoldSeatsAsFailure() {
        String md = ReportWriter.markdown(report(500, 0, false, 500));
        assertThat(md).contains("**FAIL ❌**").contains("| Seats marked SOLD = distinct seats in orders | no ❌ |");
    }

    @Test
    void marksSuccessCountMismatchAsFailure() {
        LoadTestReport r = report(500, 0, true, 499);
        assertThat(r.passed()).isFalse();
        assertThat(ReportWriter.markdown(r)).contains("**FAIL ❌**").contains("| 499 = 500 ❌ |");
    }
}
