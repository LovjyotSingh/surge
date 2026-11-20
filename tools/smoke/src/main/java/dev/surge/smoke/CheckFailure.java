package dev.surge.smoke;

/** A check did not hold. The message is printed after FAIL, so it never contains a token or a password. */
public final class CheckFailure extends RuntimeException {

    public CheckFailure(String message) {
        super(message);
    }
}
