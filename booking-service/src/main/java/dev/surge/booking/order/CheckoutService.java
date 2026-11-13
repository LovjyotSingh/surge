package dev.surge.booking.order;

import dev.surge.booking.error.HoldNotFoundException;
import dev.surge.booking.error.PaymentFailedException;
import dev.surge.booking.error.SeatConflictException;
import dev.surge.booking.event.SeatState;
import dev.surge.booking.hold.ActiveHold;
import dev.surge.booking.hold.HoldStore;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.inventory.SeatInfo;
import dev.surge.booking.payment.PaymentGateway;
import dev.surge.booking.payment.PaymentRequest;
import dev.surge.booking.payment.PaymentResult;
import dev.surge.booking.pricing.PriceCalculator;
import dev.surge.booking.pricing.PricingContext;
import dev.surge.booking.pricing.TierPrices;
import dev.surge.booking.stream.SeatEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a checkout. Deliberately NOT transactional: the payment call and the Redis calls must stay outside
 * the DB transaction, which lives only inside {@link OrderWriter#confirm}.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final IdempotencyStore idempotency;
    private final HoldStore holds;
    private final InventoryCache inventory;
    private final PriceCalculator calculator;
    private final TierPrices tierPrices;
    private final PaymentGateway payments;
    private final OrderWriter writer;
    private final OrderReader reader;
    private final SeatEventPublisher publisher;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public CheckoutService(IdempotencyStore idempotency, HoldStore holds, InventoryCache inventory,
                           PriceCalculator calculator, TierPrices tierPrices, PaymentGateway payments,
                           OrderWriter writer, OrderReader reader, SeatEventPublisher publisher,
                           StringRedisTemplate redis, Clock clock) {
        this.idempotency = idempotency;
        this.holds = holds;
        this.inventory = inventory;
        this.calculator = calculator;
        this.tierPrices = tierPrices;
        this.payments = payments;
        this.writer = writer;
        this.reader = reader;
        this.publisher = publisher;
        this.redis = redis;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(String userId, String idempotencyKey, String holdId) {
        Optional<Long> existing = idempotency.begin(userId, idempotencyKey);
        if (existing.isPresent()) {
            return new CheckoutOutcome(reader.getForUser(userId, existing.get()), true);
        }
        try {
            // Redis may have lost the key (flush/eviction): the orders table is the durable record.
            Optional<OrderResponse> already = reader.findByIdempotencyKey(userId, idempotencyKey);
            if (already.isPresent()) {
                return replay(userId, idempotencyKey, already.get());
            }
            return placeOrder(userId, idempotencyKey, holdId);
        } catch (RuntimeException e) {
            // Compare-and-delete: only an IN_PROGRESS marker is released, never a completed order id.
            idempotency.abort(userId, idempotencyKey);
            throw e;
        }
    }

    private CheckoutOutcome replay(String userId, String idempotencyKey, OrderResponse order) {
        try {
            idempotency.complete(userId, idempotencyKey, order.orderId());
        } catch (RuntimeException e) {
            log.error("Failed to record idempotency result: orderId={} key={}", order.orderId(), idempotencyKey, e);
        }
        return new CheckoutOutcome(order, true);
    }

    private CheckoutOutcome placeOrder(String userId, String idempotencyKey, String holdId) {
        Instant now = clock.instant(); // read once: pricing and createdAt agree
        ActiveHold hold = holds.find(holdId).filter(h -> h.userId().equals(userId))
                .orElseThrow(HoldNotFoundException::new);
        EventInventory inv = inventory.get(hold.eventId());
        Map<Long, BigDecimal> prices = priceSeats(hold, inv, now);
        BigDecimal total = prices.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);

        PaymentResult payment = payments.charge(new PaymentRequest(userId + ":" + idempotencyKey, userId, total));
        if (!payment.approved()) {
            throw new PaymentFailedException(payment.declineReason());
        }

        Order order;
        try {
            order = writer.confirm(hold, prices, idempotencyKey, now.truncatedTo(ChronoUnit.MICROS));
        } catch (RuntimeException e) {
            if (!(e instanceof SeatConflictException)) {
                // In-doubt commit: the commit may have succeeded even though an exception surfaced.
                Optional<OrderResponse> committed = lookupQuietly(userId, idempotencyKey);
                if (committed.isPresent()) {
                    finalizeAfterCommit(hold);
                    return replay(userId, idempotencyKey, committed.get());
                }
            }
            // Money was taken but the seats were not sold: give it back.
            refundQuietly(payment.paymentId(), e);
            if (e instanceof SeatConflictException) {
                // A unique violation on (user, idempotency key) also lands here: the order already exists.
                Optional<OrderResponse> existing = lookupQuietly(userId, idempotencyKey);
                if (existing.isPresent()) {
                    return replay(userId, idempotencyKey, existing.get());
                }
            }
            throw e;
        }

        // The DB commit is the source of truth. From here on nothing may fail the request.
        finalizeAfterCommit(hold);
        OrderResponse response = OrderResponse.from(order, inv);
        try {
            idempotency.complete(userId, idempotencyKey, order.getId());
        } catch (RuntimeException e) {
            log.error("Failed to record idempotency result: orderId={} key={}", order.getId(), idempotencyKey, e);
        }
        return new CheckoutOutcome(response, false);
    }

    private void finalizeAfterCommit(ActiveHold hold) {
        try {
            holds.finalizeSold(hold);
        } catch (RuntimeException e) {
            log.error("finalizeSold failed after commit: holdId={}", hold.holdId(), e);
        }
        publisher.publish(hold.eventId(), hold.seatIds(), SeatState.SOLD);
    }

    private Optional<OrderResponse> lookupQuietly(String userId, String key) {
        try {
            return reader.findByIdempotencyKey(userId, key);
        } catch (RuntimeException e) {
            log.error("Order lookup failed during recovery: userId={} key={}", userId, key, e);
            return Optional.empty();
        }
    }

    private Map<Long, BigDecimal> priceSeats(ActiveHold hold, EventInventory inv, Instant now) {
        Long sold = redis.opsForSet().size(RedisKeys.sold(hold.eventId()));
        int available = inv.totalSeats() - (sold == null ? 0 : sold.intValue());
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        for (Long seatId : hold.seatIds()) {
            SeatInfo info = inv.seats().get(seatId);
            if (info == null) {
                throw new HoldNotFoundException();
            }
            prices.put(seatId, calculator.price(new PricingContext(info.tier(), tierPrices.base(info.tier()),
                    now, inv.saleOpensAt(), available, inv.totalSeats())).setScale(2));
        }
        return prices;
    }

    private void refundQuietly(String paymentId, RuntimeException cause) {
        try {
            payments.refund(paymentId);
        } catch (RuntimeException refundFailure) {
            log.error("REFUND FAILED, manual action needed: paymentId={}", paymentId, refundFailure);
            cause.addSuppressed(refundFailure);
        }
    }
}
