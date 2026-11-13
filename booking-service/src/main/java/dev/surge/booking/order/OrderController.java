package dev.surge.booking.order;

import dev.surge.booking.error.InvalidIdempotencyKeyException;
import dev.surge.booking.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/orders")
public class OrderController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 64;

    private final CheckoutService checkout;
    private final OrderReader reader;

    public OrderController(CheckoutService checkout, OrderReader reader) {
        this.checkout = checkout;
        this.reader = reader;
    }

    /** {@code @CurrentUser} is declared before the body so a missing user (401) wins over body validation (400). */
    @PostMapping
    public ResponseEntity<OrderResponse> checkout(@CurrentUser String userId,
                                                  @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String key,
                                                  @Valid @RequestBody CheckoutRequest request) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
        CheckoutOutcome outcome = checkout.checkout(userId, key, request.holdId());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(outcome.order());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.order());
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@CurrentUser String userId, @PathVariable long orderId) {
        return reader.getForUser(userId, orderId);
    }
}
