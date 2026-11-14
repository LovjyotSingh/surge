package dev.surge.booking.payment;

/** The payment service could not be reached in time (open circuit, I/O error, timeout or 5xx after retries). Nothing was charged as far as booking knows. */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
