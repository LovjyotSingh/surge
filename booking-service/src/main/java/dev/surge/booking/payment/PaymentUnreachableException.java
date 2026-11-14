package dev.surge.booking.payment;

/**
 * The call could not even be made: no instance in the load balancer, or another client-side failure that is not an
 * HTTP answer. Recorded by the circuit breaker, not retried.
 */
class PaymentUnreachableException extends RuntimeException {

    PaymentUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
