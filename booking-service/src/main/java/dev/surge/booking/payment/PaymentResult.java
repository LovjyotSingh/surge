package dev.surge.booking.payment;

public record PaymentResult(boolean approved, String paymentId, String declineReason) {
}
