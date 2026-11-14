package dev.surge.catalog.error;

/** The CSV as a whole cannot be imported (empty, wrong header, unreadable): a 400, not a per-line error. */
public class InvalidImportException extends RuntimeException {
    public InvalidImportException(String message) {
        super(message);
    }
}
