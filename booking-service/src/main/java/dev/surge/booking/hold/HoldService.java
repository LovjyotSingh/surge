package dev.surge.booking.hold;

import dev.surge.booking.error.HoldExistsException;
import dev.surge.booking.error.InvalidSeatsException;
import dev.surge.booking.error.SaleNotOpenException;
import dev.surge.booking.error.SeatsTakenException;
import dev.surge.booking.event.SeatState;
import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.stream.SeatEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HoldService {

    private final InventoryCache inventory;
    private final HoldStore store;
    private final SeatEventPublisher publisher;
    private final Clock clock;
    private final Duration ttl;

    public HoldService(InventoryCache inventory, HoldStore store, SeatEventPublisher publisher, Clock clock,
                       @Value("${surge.hold.ttl}") Duration ttl) {
        this.inventory = inventory;
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.ttl = ttl;
    }

    public HoldResponse hold(String userId, long eventId, List<Long> seatIds) {
        EventInventory inv = inventory.get(eventId);

        Set<Long> offending = new LinkedHashSet<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : seatIds) {
            if (!seen.add(id) || !inv.contains(id)) {
                offending.add(id);
            }
        }
        if (!offending.isEmpty()) {
            throw new InvalidSeatsException(List.copyOf(offending));
        }
        if (clock.instant().isBefore(inv.saleOpensAt())) {
            throw new SaleNotOpenException(inv.saleOpensAt());
        }

        return switch (store.tryHold(userId, eventId, seatIds, ttl)) {
            case HoldResult.Held h -> {
                publisher.publish(eventId, seatIds, SeatState.HELD);
                yield new HoldResponse(h.holdId(), eventId, seatIds, h.expiresAt());
            }
            case HoldResult.SeatsTaken t -> throw new SeatsTakenException(t.seatIds());
            case HoldResult.HoldExists e -> throw new HoldExistsException(e.holdId());
        };
    }
}
