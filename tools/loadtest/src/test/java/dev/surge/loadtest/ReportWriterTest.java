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
            succeeded, 2, 0, 0, 0, 0, 1, ZERO, ZERO, 0);
    }

    @Test
    void writesJsonAndMarkdown(@TempDir Path dir) throws Exception {
        var r = new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), 10000, 1, 500, 500, 0, true,
            500, 640, 321, 7, 12, 0, 4200, new LatencyStats(5, 20, 40, 90), new LatencyStats(8, 25, 50, 120), 0);

        List<Path> files = ReportWriter.write(dir, r);

        assertThat(files).extracting(p -> p.getFileName().toString())
            .containsExactly("loadtest-20261112-120000.json", "loadtest-20261112-120000.md");
        String md = Files.readString(dir.resolve("loadtest-20261112-120000.md"));
        assertThat(md).contains("**PASS ✅**")
            .contains("| Oversold (a seat in two order lines) | 0 ✅ |")
            .contains("| Seats marked SOLD = distinct seats in orders | yes ✅ |")
            .contains("| Buyers who got a 201 = seats sold | 500 = 500 ✅ |")
            .contains("| Sold | 500 |").contains("| Requests | 640 |")
            .contains("| Busy retries (503 service-busy, retried) | 12 |");
        assertThat(Files.readString(dir.resolve("loadtest-20261112-120000.json")))
            .contains("\"oversold\"").contains("\"consistent\"").contains("\"sold\"").contains("2026-11-12T06:30:00Z")
            .containsPattern("\"busyRetries\"\\s*:\\s*12");
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

    private static LoadTestReport run(int users, int sold, int conflicts, int errors, int unauthorized) {
        return new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), users, 1, 500, sold, 0, true, sold,
            users, conflicts, 0, 0, errors, 1, ZERO, ZERO, unauthorized);
    }

    @Test
    void aRunThatSoldNothingFailsEvenThoughNoInvariantBroke() {
        // The 2026-11-17 trial: every hold got a 503, so nothing could be oversold, yet nothing was tested.
        LoadTestReport r = run(10000, 0, 326, 10000, 0);

        assertThat(r.invariantsHold()).isTrue();
        assertThat(r.nothingSold()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(ReportWriter.markdown(r)).contains("**FAIL ❌**").doesNotContain("PASS")
            .contains("| Seats sold (at least one) | 0 ❌ |")
            .contains("| Buyers ending in an error (at most 1 %) | 10000 of 10000 ❌ |");
    }

    @Test
    void errorsAboveOnePercentOfBuyersFail() {
        assertThat(run(10000, 500, 0, 100, 0).passed()).isTrue();   // exactly 1 %
        LoadTestReport r = run(10000, 500, 0, 101, 0);
        assertThat(r.tooManyErrors()).isTrue();
        assertThat(r.passed()).isFalse();
        assertThat(ReportWriter.markdown(r)).contains("**FAIL ❌**").contains("| 101 of 10000 ❌ |");
    }

    @Test
    void conflictsFromASoldOutEventAreNotErrors() {
        LoadTestReport r = run(10000, 500, 9500, 0, 0);
        assertThat(r.passed()).isTrue();
        assertThat(ReportWriter.markdown(r)).contains("**PASS ✅**")
            .contains("| Seats sold (at least one) | 500 ✅ |")
            .contains("| Buyers ending in an error (at most 1 %) | 0 of 10000 ✅ |");
    }

    @Test
    void rejectedBuyersStillReadNotValidRatherThanFail() {
        // Nothing sold is the expected consequence of rejected tokens: the run is not valid, not a FAIL.
        String md = ReportWriter.markdown(run(3, 0, 0, 0, 3));
        assertThat(md).contains("**NOT VALID: 3 buyer tokens rejected**").doesNotContain("PASS").doesNotContain("FAIL");
    }
}
