package dev.surge.booking.order;

import dev.surge.booking.error.InvalidParameterException;
import dev.surge.common.security.CurrentIdentity;
import dev.surge.common.security.InternalTokens;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * notification-service's view of an order, for the ticket it renders on the owner's behalf. The gateway has no route
 * to /internal; booking's identity chain lets only {@code svc:notification} in ({@code AccessRulesConfig}), and the
 * controller checks again before reading anything. The order comes back only when it belongs to {@code userId}
 * (the same view and rule as {@code GET /api/bookings/orders/{id}}): someone else's order and a missing one are the
 * same 404.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderReader reader;

    public InternalOrderController(OrderReader reader) {
        this.reader = reader;
    }

    /**
     * Both parameters bind as plain strings (nothing can fail to bind), so the caller check runs before any parameter
     * is judged: only {@code svc:notification} ever hears that one is malformed.
     */
    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable String orderId, @RequestParam(required = false) String userId) {
        CurrentIdentity.requireService(InternalTokens.SVC_NOTIFICATION);
        long id = parseOrderId(orderId);
        if (!InternalTokens.isUserId(userId)) {
            throw new InvalidParameterException("userId must be a user id");
        }
        return reader.getForUser(userId, id);
    }

    private static long parseOrderId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the same message
        }
        throw new InvalidParameterException("orderId must be a positive whole number");
    }
}
