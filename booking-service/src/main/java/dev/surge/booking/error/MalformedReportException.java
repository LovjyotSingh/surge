package dev.surge.booking.error;

public class MalformedReportException extends RuntimeException {
    public MalformedReportException() {
        super("Report body is not valid JSON");
    }
}
