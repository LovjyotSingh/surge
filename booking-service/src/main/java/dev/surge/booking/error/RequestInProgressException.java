package dev.surge.booking.error;

public class RequestInProgressException extends RuntimeException {
    public RequestInProgressException() {
        super("A request with this idempotency key is already in progress");
    }
}
