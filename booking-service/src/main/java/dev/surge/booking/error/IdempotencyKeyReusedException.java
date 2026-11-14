package dev.surge.booking.error;

/** payment-service saw the same reference with a different user or amount: the Idempotency-Key was reused. */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different payment. Use a new key.");
    }
}
