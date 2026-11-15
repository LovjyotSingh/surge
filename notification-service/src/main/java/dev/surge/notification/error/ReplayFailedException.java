package dev.surge.notification.error;

/**
 * A replay could not be completed because the broker or its topology is unhealthy. Nothing is lost: the detail
 * says where the message in question is. The cause (when there is one) is kept for the log.
 */
public class ReplayFailedException extends RuntimeException {

    public ReplayFailedException(String detail, int replayedBefore, Throwable cause) {
        super(detail + "; " + replayedBefore + " message(s) replayed", cause);
    }
}
