package dev.surge.booking.order;

import dev.surge.booking.error.SeatConflictException;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.hold.ActiveHold;
import dev.surge.outbox.OutboxMessage;
import dev.surge.outbox.OutboxRepository;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.OrderConfirmed;
import dev.surge.common.messaging.TicketRequested;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single DB transaction that sells seats. Double-sale defences here: the status check, the {@code @Version}
 * optimistic lock on seat, and the unique {@code order_item.seat_id} constraint.
 */
@Component
public class OrderWriter {

    private final SeatRepository seats;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final JsonMapper json;

    public OrderWriter(SeatRepository seats, OrderRepository orders, OutboxRepository outbox, JsonMapper json) {
        this.seats = seats;
        this.orders = orders;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public Order confirm(ActiveHold hold, Map<Long, BigDecimal> prices, String idempotencyKey, Instant now) {
        try {
            return doConfirm(hold, prices, idempotencyKey, now);
        } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
            throw new SeatConflictException();
        }
    }

    private Order doConfirm(ActiveHold hold, Map<Long, BigDecimal> prices, String idempotencyKey,
                            Instant now) {
        List<Seat> found = seats.findAllById(hold.seatIds());
        if (found.size() != hold.seatIds().size()) {
            throw new SeatConflictException();
        }
        for (Seat seat : found) {
            if (seat.getStatus() != SeatStatus.AVAILABLE || !seat.getEventId().equals(hold.eventId())) {
                throw new SeatConflictException();
            }
            seat.setStatus(SeatStatus.SOLD);
        }
        seats.saveAllAndFlush(found);

        BigDecimal total = hold.seatIds().stream().map(prices::get).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        Order order = new Order(hold.userId(), hold.eventId(), OrderStatus.CONFIRMED, total, idempotencyKey, now);
        for (Long seatId : hold.seatIds()) {
            order.addItem(new OrderItem(seatId, prices.get(seatId)));
        }
        order = orders.saveAndFlush(order);

        String aggregateId = String.valueOf(order.getId());
        OrderConfirmed confirmed = new OrderConfirmed(UUID.randomUUID(), order.getId(), hold.userId(),
                hold.eventId(), hold.seatIds(), total, now);
        TicketRequested ticket = new TicketRequested(UUID.randomUUID(), order.getId(), hold.userId(),
                hold.eventId(), hold.seatIds(), now);
        outbox.save(new OutboxMessage(aggregateId, "OrderConfirmed", MessageTypes.ORDERS_DESTINATION,
                json.writeValueAsString(confirmed), now));
        outbox.save(new OutboxMessage(aggregateId, "TicketRequested", MessageTypes.TICKET_REQUESTED_DESTINATION,
                json.writeValueAsString(ticket), now));
        return order;
    }
}
