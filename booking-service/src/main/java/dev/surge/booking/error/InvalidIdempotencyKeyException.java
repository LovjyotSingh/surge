package dev.surge.booking.error;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key header is required and must be 1-64 characters");
    }
}
