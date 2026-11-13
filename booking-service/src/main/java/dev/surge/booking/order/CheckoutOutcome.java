package dev.surge.booking.order;

public record CheckoutOutcome(OrderResponse order, boolean replayed) {
}
