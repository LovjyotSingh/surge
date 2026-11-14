package dev.surge.payment;

/** What a lookup by reference returns: the stored charge result plus whether it was refunded since. */
public record PaymentStatus(boolean approved, String paymentId, String declineReason, boolean refunded) {
}
