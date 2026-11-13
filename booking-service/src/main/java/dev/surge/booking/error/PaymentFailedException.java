package dev.surge.booking.error;

public class PaymentFailedException extends RuntimeException {
    private final String reason;

    public PaymentFailedException(String reason) {
        super("Payment failed: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
