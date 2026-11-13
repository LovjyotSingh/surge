package dev.surge.booking.admin;

/** Oversell check for one event: {@code oversold = orderItems - distinctSeatsSold}, and must always be 0. */
public record IntegrityReport(long eventId, int totalSeats, int soldSeats, int orderItems, int distinctSeatsSold,
                              int oversold) {
}
