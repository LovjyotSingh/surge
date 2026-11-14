package dev.surge.common.messaging;

/** One row of seats in an event's layout: {@code seats} seats in row {@code rowLabel}, priced as {@code tier}. */
public record RowSpec(String rowLabel, int seats, String tier) {
}
