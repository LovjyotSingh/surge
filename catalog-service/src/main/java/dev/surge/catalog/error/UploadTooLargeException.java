package dev.surge.catalog.error;

public class UploadTooLargeException extends RuntimeException {
    public UploadTooLargeException(long maxBytes) {
        super("The file is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit");
    }
}
