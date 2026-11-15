package dev.surge.common.security;

/** An internal identity token that must not be trusted. The message says why, never what the token contained. */
public class InvalidInternalTokenException extends RuntimeException {

    public InvalidInternalTokenException(String reason) {
        super(reason);
    }
}
