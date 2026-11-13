package dev.surge.booking.order;

import dev.surge.booking.error.HoldNotFoundException;
import dev.surge.booking.error.PaymentFailedException;
import dev.surge.booking.error.RequestInProgressException;
import dev.surge.booking.error.SeatConflictException;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatState;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.hold.ActiveHold;
import dev.surge.booking.hold.HoldStore;
import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.payment.PaymentGateway;
import dev.surge.booking.payment.PaymentRequest;
import dev.surge.booking.payment.PaymentResult;
import dev.surge.booking.stream.SeatEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a checkout. Deliberately NOT transactional: the payment call and the Redis calls must stay outside
 * the DB transaction, which lives only inside {@link OrderWriter#confirm}. The amount charged is the price locked
 * on the hold when it was made; checkout never re-prices.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final IdempotencyStore idempotency;
    private final HoldStore holds;
    private final InventoryCache inventory;
    private final PaymentGateway payments;
    private final OrderWriter writer;
    private final OrderReader reader;
    private final SeatRepository seats;
    private final SeatEventPublisher publisher;
    private final Clock clock;

    public CheckoutService(IdempotencyStore idempotency, HoldStore holds, InventoryCache inventory,
                           PaymentGateway payments, OrderWriter writer, OrderReader reader, SeatRepository seats,
                           SeatEventPublisher publisher, Clock clock) {
        this.idempotency = idempotency;
        this.holds = holds;
        this.inventory = inventory;
        this.payments = payments;
        this.writer = writer;
        this.reader = reader;
        this.seats = seats;
        this.publisher = publisher;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(String userId, String idempotencyKey, String holdId) {
        Optional<OrderResponse> previous = claim(userId, idempotencyKey);
        if (previous.isPresent()) {
            return new CheckoutOutcome(previous.get(), true);
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

    /**
     * Claims the idempotency key (empty result: this request now owns it) or returns the order it already produced.
     * A stored order id whose order no longer exists is stale (an admin reset deletes orders but keeps keys): the key
     * is taken over with an atomic compare-and-set and the request runs as a new one.
     */
    private Optional<OrderResponse> claim(String userId, String key) {
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<Long> stored = idempotency.begin(userId, key);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            Optional<OrderResponse> order = reader.findForUser(userId, stored.get());
            if (order.isPresent()) {
                return order;
            }
            if (idempotency.takeOver(userId, key, stored.get())) {
                log.info("Idempotency key pointed at a deleted order; treating as new: userId={} staleOrderId={}",
                        userId, stored.get());
                return Optional.empty();
            }
            // The key changed under us (another request took it over or completed): look again.
        }
        throw new RequestInProgressException();
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
        Instant now = clock.instant();
        ActiveHold hold = holds.find(holdId).filter(h -> h.userId().equals(userId))
                .orElseThrow(HoldNotFoundException::new);
        EventInventory inv = inventory.get(hold.eventId());
        Map<Long, BigDecimal> prices = lockedPrices(hold);
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
                // The seats went to another order: give the hold back so the user can pick again right away.
                releaseAfterConflict(hold);
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

    /** The prices locked when the hold was made. A hold without them (written by an older version) cannot be paid. */
    private static Map<Long, BigDecimal> lockedPrices(ActiveHold hold) {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        for (Long seatId : hold.seatIds()) {
            BigDecimal price = hold.prices().get(seatId);
            if (price == null) {
                throw new HoldNotFoundException();
            }
            prices.put(seatId, price.setScale(2));
        }
        return prices;
    }

    private void finalizeAfterCommit(ActiveHold hold) {
        try {
            holds.finalizeSold(hold);
        } catch (RuntimeException e) {
            log.error("finalizeSold failed after commit: holdId={}", hold.holdId(), e);
        }
        publisher.publish(hold.eventId(), hold.seatIds(), SeatState.SOLD);
    }

    /**
     * Removes a hold whose seats were lost to another order (Redis had lost track of a sale). Nothing is added to the
     * sold set; open seat maps are told the MySQL truth: sold seats SOLD, the rest AVAILABLE again. Best effort.
     */
    private void releaseAfterConflict(ActiveHold hold) {
        try {
            holds.release(hold);
            List<Long> sold = new ArrayList<>();
            List<Long> free = new ArrayList<>();
            for (Seat seat : seats.findAllById(hold.seatIds())) {
                (seat.getStatus() == SeatStatus.SOLD ? sold : free).add(seat.getId());
            }
            if (!sold.isEmpty()) {
                publisher.publish(hold.eventId(), sold, SeatState.SOLD);
            }
            if (!free.isEmpty()) {
                publisher.publish(hold.eventId(), free, SeatState.AVAILABLE);
            }
        } catch (RuntimeException e) {
            log.warn("Releasing hold after a seat conflict failed: holdId={}", hold.holdId(), e);
        }
    }

    private Optional<OrderResponse> lookupQuietly(String userId, String key) {
        try {
            return reader.findByIdempotencyKey(userId, key);
        } catch (RuntimeException e) {
            log.error("Order lookup failed during recovery: userId={} key={}", userId, key, e);
            return Optional.empty();
        }
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
