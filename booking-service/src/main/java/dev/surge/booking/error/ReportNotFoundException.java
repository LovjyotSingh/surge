package dev.surge.booking.error;

public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException() {
        super("No load-test report has been stored yet");
    }
}
