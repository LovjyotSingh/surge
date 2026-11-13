package dev.surge.booking.admin;

/**
 * Oversell check for one event.
 *
 * <ul>
 *   <li>{@code oversold = orderItems - distinctSeatsSold}: two order lines for one seat. The {@code UNIQUE(seat_id)}
 *       constraint on {@code order_item} makes this impossible, so it is a guard that the constraint is in place;
 *       it must always be 0.</li>
 *   <li>{@code consistent = soldSeats == distinctSeatsSold}: every seat marked SOLD has exactly one order line and
 *       no order line points at a seat that is still AVAILABLE. This one can catch a real bug.</li>
 * </ul>
 */
public record IntegrityReport(long eventId, int totalSeats, int soldSeats, int orderItems, int distinctSeatsSold,
                              int oversold, boolean consistent) {

    public static IntegrityReport of(long eventId, int totalSeats, int soldSeats, int orderItems,
                                     int distinctSeatsSold) {
        return new IntegrityReport(eventId, totalSeats, soldSeats, orderItems, distinctSeatsSold,
                orderItems - distinctSeatsSold, soldSeats == distinctSeatsSold);
    }
}
