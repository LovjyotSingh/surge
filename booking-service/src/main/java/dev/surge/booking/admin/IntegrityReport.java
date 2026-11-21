package dev.surge.booking.admin;

/**
 * Oversell check for one event.
 *
 * <ul>
 *   <li>{@code oversold = orderItems - distinctSeatsSold}: two order lines for one seat. The {@code UNIQUE(seat_id)}
 *       constraint on {@code order_item} makes this impossible, so it is a guard that the constraint is in place;
 *       it must always be 0.</li>
 *   <li>{@code seatMismatches}: the number of the event's seats, one by one, where "the seat is marked SOLD" and
 *       "the seat has an order line" disagree: a SOLD seat with no order line, or an order line on a seat that is not
 *       SOLD. A seat of the first kind and one of the second count 2 together; they do not cancel out.</li>
 *   <li>{@code consistent = (seatMismatches == 0)}: every seat marked SOLD has an order line and every seat with an
 *       order line is marked SOLD. This one can catch a real bug.</li>
 * </ul>
 *
 * {@code soldSeats} and {@code distinctSeatsSold} stay in the report for information (and compatibility); they are
 * counts and decide nothing on their own.
 */
public record IntegrityReport(long eventId, int totalSeats, int soldSeats, int orderItems, int distinctSeatsSold,
                              int oversold, int seatMismatches, boolean consistent) {

    public static IntegrityReport of(long eventId, int totalSeats, int soldSeats, int orderItems,
                                     int distinctSeatsSold, int seatMismatches) {
        return new IntegrityReport(eventId, totalSeats, soldSeats, orderItems, distinctSeatsSold,
                orderItems - distinctSeatsSold, seatMismatches, seatMismatches == 0);
    }
}
