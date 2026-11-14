package dev.surge.notification.error;

public class MissingUserException extends RuntimeException {

    public MissingUserException() {
        super("A valid X-User-Id header is required");
    }
}
