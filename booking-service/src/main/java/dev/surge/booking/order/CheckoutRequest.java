package dev.surge.booking.order;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String holdId) {
}
