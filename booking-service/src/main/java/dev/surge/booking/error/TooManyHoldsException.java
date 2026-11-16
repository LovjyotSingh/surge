package dev.surge.booking.error;

/** The user already holds seats in the maximum number of events at once (S5: no hoarding across events). */
public class TooManyHoldsException extends RuntimeException {
    private final int max;

    public TooManyHoldsException(int max) {
        super("You already hold seats in " + max + " events. Buy or let a hold expire before holding more.");
        this.max = max;
    }

    public int max() {
        return max;
    }
}
