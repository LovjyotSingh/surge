package dev.surge.notification.error;

/** A dead-lettered message could not be put back on the ticket queue (unroutable or not confirmed). It stays in the DLQ. */
public class ReplayFailedException extends RuntimeException {

    public ReplayFailedException(String message, int replayedBefore) {
        super(message + " (" + replayedBefore + " replayed before the failure; the failed message is still in tickets.dlq)");
    }
}
