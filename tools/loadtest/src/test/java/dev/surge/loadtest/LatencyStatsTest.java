package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

    @Test
    void percentilesNearestRank() {
        var s = LatencyStats.of(LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(s).isEqualTo(new LatencyStats(50, 95, 99, 100));
    }

    @Test
    void emptyLatencies() {
        assertThat(LatencyStats.of(List.of())).isEqualTo(new LatencyStats(0, 0, 0, 0));
    }
}
