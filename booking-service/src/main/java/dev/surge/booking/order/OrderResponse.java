package dev.surge.booking.order;

import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.SeatInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(long orderId, long eventId, String status, BigDecimal totalInr, List<OrderLine> lines,
                            Instant createdAt) {

    /** Builds the response from a persisted order; row label and seat number come from the inventory cache. */
    static OrderResponse from(Order order, EventInventory inventory) {
        List<OrderLine> lines = order.getItems().stream().map(i -> {
            SeatInfo info = inventory.seats().get(i.getSeatId());
            return new OrderLine(i.getSeatId(), info == null ? null : info.rowLabel(),
                    info == null ? 0 : info.number(), i.getPriceInr());
        }).toList();
        return new OrderResponse(order.getId(), order.getEventId(), order.getStatus().name(), order.getTotalInr(),
                lines, order.getCreatedAt());
    }
}
