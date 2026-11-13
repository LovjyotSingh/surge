package dev.surge.booking.error;

public class ReportTooLargeException extends RuntimeException {
    public ReportTooLargeException(int maxBytes) {
        super("Report exceeds the " + maxBytes + "-byte limit");
    }
}
