package dev.surge.payment.error;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String id) {
        super("Payment not found: " + id);
    }
}
