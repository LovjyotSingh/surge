package dev.surge.catalog.search;

/** Elasticsearch cannot be reached (or its index is not there yet); the API answers 503 {@code search-unavailable}. */
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
