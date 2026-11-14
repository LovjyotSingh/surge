package dev.surge.payment.error;

/** A simulated provider outage (chaos errorRate). */
public class ProviderErrorException extends RuntimeException {
    public ProviderErrorException(String message) {
        super(message);
    }
}
