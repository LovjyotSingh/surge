package dev.surge.booking.order;

import dev.surge.booking.error.OrderNotFoundException;
import dev.surge.booking.inventory.InventoryCache;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read side for orders. Transactional so the lazy item collection can be loaded (open-in-view is off). */
@Component
public class OrderReader {

    private final OrderRepository orders;
    private final InventoryCache inventory;

    public OrderReader(OrderRepository orders, InventoryCache inventory) {
        this.orders = orders;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdempotencyKey(String userId, String key) {
        return orders.findByUserIdAndIdempotencyKey(userId, key)
                .map(o -> OrderResponse.from(o, inventory.get(o.getEventId())));
    }

    @Transactional(readOnly = true)
    public OrderResponse getForUser(String userId, long orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order, inventory.get(order.getEventId()));
    }
}
