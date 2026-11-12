package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @Test
    void writesJsonAndMarkdown(@TempDir Path dir) throws Exception {
        var r = new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), 10000, 1, 500, 500, 0,
            500, 321, 7, 0, 4200, new LatencyStats(5, 20, 40, 90), new LatencyStats(8, 25, 50, 120));

        List<Path> files = ReportWriter.write(dir, r);

        assertThat(files).extracting(p -> p.getFileName().toString())
            .containsExactly("loadtest-20261112-120000.json", "loadtest-20261112-120000.md");
        String md = Files.readString(dir.resolve("loadtest-20261112-120000.md"));
        assertThat(md).contains("Oversold: 0 ✅").contains("| Sold | 500 |");
        assertThat(Files.readString(dir.resolve("loadtest-20261112-120000.json")))
            .contains("\"oversold\"").contains("\"sold\"").contains("2026-11-12T06:30:00Z");
    }

    @Test
    void marksOversoldAsFailure() {
        var r = new LoadTestReport(Instant.parse("2026-11-12T06:30:00Z"), 10, 1, 500, 501, 1,
            1, 0, 0, 0, 1, new LatencyStats(0, 0, 0, 0), new LatencyStats(0, 0, 0, 0));
        assertThat(ReportWriter.markdown(r)).contains("Oversold: 1 ❌");
    }
}
