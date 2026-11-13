package dev.surge.booking.hold;

import java.time.Instant;
import java.util.List;

public sealed interface HoldResult permits HoldResult.Held, HoldResult.SeatsTaken, HoldResult.HoldExists {

    record Held(String holdId, Instant expiresAt) implements HoldResult {
    }

    record SeatsTaken(List<Long> seatIds) implements HoldResult {
    }

    record HoldExists(String holdId) implements HoldResult {
    }
}
