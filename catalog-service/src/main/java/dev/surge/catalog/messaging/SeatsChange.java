package dev.surge.catalog.messaging;

/** The new seatsLeft of an event after a message was applied; the search index is updated from it. */
public record SeatsChange(long eventId, int seatsLeft) {
}
