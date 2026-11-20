package dev.surge.booking.order;

import dev.surge.booking.error.InvalidIdempotencyKeyException;
import dev.surge.booking.error.InvalidParameterException;
import dev.surge.booking.web.CurrentUser;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
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
    /**
     * SEC-25: the key ends up in Redis keys, a unique constraint and the payment reference ({@code userId:key}), so
     * only this shape. The web sends a UUID (36); the load tester sends {@code runId-userId} (61 at most).
     */
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String KEY_RULE = "Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'";

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
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidParameterException(KEY_RULE);
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
