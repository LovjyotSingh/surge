package dev.surge.booking.hold;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record HoldRequest(@NotNull Long eventId, @NotEmpty @Size(max = 8) List<@NotNull Long> seatIds) {
}
