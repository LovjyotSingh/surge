package dev.surge.booking.hold;

import java.time.Instant;
import java.util.List;

public sealed interface HoldResult permits HoldResult.Held, HoldResult.SeatsTaken, HoldResult.HoldExists,
        HoldResult.TooManyHolds {

    /** The user already has the maximum number of active holds across all events. */
    record TooManyHolds(int active) implements HoldResult {
    }

    record Held(String holdId, Instant expiresAt) implements HoldResult {
    }

    record SeatsTaken(List<Long> seatIds) implements HoldResult {
    }

    record HoldExists(String holdId) implements HoldResult {
    }
}
