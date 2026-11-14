package dev.surge.booking.payment;

/**
 * A charge was (possibly) sent but its outcome is unknown: a read timeout or an I/O error after the connection was
 * made. The payment may exist at the provider, so checkout records it for reconciliation. Never thrown for an open
 * circuit, a failed connect or a missing instance: in those cases nothing was sent.
 */
public class PaymentInDoubtException extends PaymentUnavailableException {

    public PaymentInDoubtException(String message, Throwable cause) {
        super(message, cause);
    }
}
