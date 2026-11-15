package dev.surge.loadtest;

/** The run cannot start (bad credentials, minting disabled...). The message is safe to print: it never holds a secret. */
final class SetupException extends RuntimeException {

    SetupException(String message) {
        super(message);
    }
}
