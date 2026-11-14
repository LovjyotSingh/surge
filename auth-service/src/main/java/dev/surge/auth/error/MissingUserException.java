package dev.surge.auth.error;

public class MissingUserException extends RuntimeException {
    public MissingUserException() {
        super("A valid X-User-Id header is required");
    }
}
