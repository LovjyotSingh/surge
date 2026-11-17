package dev.surge.booking.error;

/** No (or a blank) {@code Idempotency-Key} header: 400 missing-idempotency-key. A malformed one is 400 validation-failed. */
public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key header is required");
    }
}
