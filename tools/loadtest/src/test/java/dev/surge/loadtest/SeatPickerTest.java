package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class SeatPickerTest {

    @Test
    void pickerFavorsHotSeats() {
        List<Long> ids = LongStream.rangeClosed(1, 500).boxed().toList();
        Random rnd = new Random(42);
        int hot = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            if (SeatPicker.pick(ids, 0.7, rnd) <= 100) {
                hot++;
            }
        }
        assertThat(hot / (double) n).isBetween(0.70, 0.80);
    }
}
