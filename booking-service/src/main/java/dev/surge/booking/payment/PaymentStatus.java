package dev.surge.booking.payment;

/** The payment provider's stored state for a reference (payment-service {@code GET /api/payments?reference=}). */
public record PaymentStatus(boolean approved, String paymentId, String declineReason, boolean refunded) {
}
