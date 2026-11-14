package dev.surge.payment.error;

public class ReferenceConflictException extends RuntimeException {
    public ReferenceConflictException(String reference) {
        super("Reference was already used with a different user or amount");
    }
}
