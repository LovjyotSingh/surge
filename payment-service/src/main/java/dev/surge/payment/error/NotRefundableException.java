package dev.surge.payment.error;

public class NotRefundableException extends RuntimeException {
    public NotRefundableException(String id) {
        super("Payment " + id + " was declined and cannot be refunded");
    }
}
