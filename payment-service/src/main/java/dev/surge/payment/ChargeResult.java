package dev.surge.payment;

public record ChargeResult(boolean approved, String paymentId, String declineReason) {
}
