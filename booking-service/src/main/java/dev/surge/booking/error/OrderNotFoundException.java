package dev.surge.booking.error;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(long orderId) {
        super("Order not found: " + orderId);
    }
}
