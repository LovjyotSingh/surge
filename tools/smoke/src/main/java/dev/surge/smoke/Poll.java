package dev.surge.smoke;

import java.time.Duration;
import java.util.function.Supplier;

/** Poll-until-deadline helper for things that happen asynchronously (emails, Kafka consumers, a breaker closing). */
public final class Poll {

    private static final ThreadLocal<String> LAST_NOTE = new ThreadLocal<>();

    private Poll() {
    }

    /**
     * Calls {@code attempt} until it returns a non-null value, at most until {@code timeout} has passed (the first
     * attempt is immediate, the last one happens at or after the deadline). {@code attempt} may call {@link #note}
     * to say why it is not ready yet; that text ends up in the failure. A {@link CheckFailure} thrown by
     * {@code attempt} ends the poll at once.
     */
    public static <T> T until(String what, Duration timeout, Duration interval, Supplier<T> attempt) {
        long deadline = System.nanoTime() + timeout.toNanos();
        LAST_NOTE.remove();
        while (true) {
            T value = attempt.get();
            if (value != null) {
                return value;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                String note = LAST_NOTE.get();
                throw new CheckFailure("gave up waiting for " + what + " within "
                    + String.format(java.util.Locale.ROOT, "%.1f", timeout.toMillis() / 1000.0) + " s"
                    + (note == null ? "" : " (last seen: " + note + ")"));
            }
            try {
                Thread.sleep(Math.min(interval.toMillis(), Math.max(1, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CheckFailure("interrupted while waiting for " + what);
            }
        }
    }

    /** Records why the current attempt is not ready (shown only if the poll runs out). */
    public static void note(String text) {
        LAST_NOTE.set(text);
    }
}
