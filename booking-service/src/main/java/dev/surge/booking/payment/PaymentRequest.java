package dev.surge.booking.payment;

import java.math.BigDecimal;

public record PaymentRequest(String reference, String userId, BigDecimal amountInr) {
}
