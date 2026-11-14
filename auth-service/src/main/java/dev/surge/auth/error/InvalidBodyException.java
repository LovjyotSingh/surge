package dev.surge.auth.error;

import java.util.Map;

/** A hand-parsed request body was malformed (errors == null) or failed validation (field -> message). */
public class InvalidBodyException extends RuntimeException {
    private final transient Map<String, String> errors;

    public InvalidBodyException(Map<String, String> errors) {
        super(errors == null ? "Request body is missing or could not be parsed" : "Request validation failed");
        this.errors = errors;
    }

    public Map<String, String> errors() {
        return errors;
    }
}
