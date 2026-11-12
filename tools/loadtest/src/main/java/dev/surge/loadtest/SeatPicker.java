package dev.surge.loadtest;

import java.util.List;
import java.util.random.RandomGenerator;

public final class SeatPicker {

    private SeatPicker() {
    }

    /** The first min(100, size/5) seats are hot; picked with probability hotShare, else uniform over all. */
    public static long pick(List<Long> seatIds, double hotShare, RandomGenerator rnd) {
        int hot = Math.min(100, seatIds.size() / 5);
        if (hot > 0 && rnd.nextDouble() < hotShare) {
            return seatIds.get(rnd.nextInt(hot));
        }
        return seatIds.get(rnd.nextInt(seatIds.size()));
    }
}
