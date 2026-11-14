package dev.surge.auth.error;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("Admin role required");
    }
}
