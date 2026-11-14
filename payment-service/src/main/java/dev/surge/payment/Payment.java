package dev.surge.payment;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

final class Payment {
    final String reference;
    final String paymentId;
    final String userId;
    final BigDecimal amountInr;
    final boolean approved;
    final String declineReason;
    final AtomicBoolean refunded = new AtomicBoolean();

    Payment(String reference, String paymentId, String userId, BigDecimal amountInr, boolean approved,
            String declineReason) {
        this.reference = reference;
        this.paymentId = paymentId;
        this.userId = userId;
        this.amountInr = amountInr;
        this.approved = approved;
        this.declineReason = declineReason;
    }

    boolean sameRequest(ChargeRequest req) {
        return userId.equals(req.userId()) && amountInr.compareTo(req.amountInr()) == 0;
    }

    ChargeResult result() {
        return new ChargeResult(approved, paymentId, declineReason);
    }
}
