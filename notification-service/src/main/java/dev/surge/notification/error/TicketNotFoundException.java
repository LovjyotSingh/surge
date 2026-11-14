package dev.surge.notification.error;

/** Also used when the ticket exists but belongs to someone else, so an order id cannot be probed. */
public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(long orderId) {
        super("No ticket for order " + orderId);
    }
}
