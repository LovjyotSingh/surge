package dev.surge.booking.payment;

import java.util.Optional;

public interface PaymentGateway {
    PaymentResult charge(PaymentRequest r);

    void refund(String paymentId);

    /** What the provider holds for this reference; empty when it never saw it. Used to reconcile in-doubt charges. */
    Optional<PaymentStatus> status(String reference);
}
