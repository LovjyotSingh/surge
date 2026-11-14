package dev.surge.payment.error;

import java.util.Map;

public class InvalidBodyException extends RuntimeException {
    private final Map<String, String> errors;

    public InvalidBodyException(Map<String, String> errors) {
        super("Request validation failed");
        this.errors = errors;
    }

    public Map<String, String> errors() {
        return errors;
    }
}
