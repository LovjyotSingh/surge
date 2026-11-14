package dev.surge.payment.error;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("This action requires the ADMIN role");
    }
}
