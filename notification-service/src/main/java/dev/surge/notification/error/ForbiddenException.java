package dev.surge.notification.error;

public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("This endpoint needs the ADMIN role");
    }
}
