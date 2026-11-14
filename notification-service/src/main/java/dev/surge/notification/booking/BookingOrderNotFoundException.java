package dev.surge.notification.booking;

public class BookingOrderNotFoundException extends RuntimeException {

    public BookingOrderNotFoundException(long orderId) {
        super("Booking has no order " + orderId + " for this user");
    }
}
