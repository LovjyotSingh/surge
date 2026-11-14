package dev.surge.booking.payment;

/**
 * A read timeout: the payment service accepted the request but did not answer in time. Recorded by the circuit
 * breaker like any I/O failure, but deliberately NOT retried (the user is already waiting 2 s; a retry would double that).
 */
class PaymentTimeoutException extends RuntimeException {

    PaymentTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
