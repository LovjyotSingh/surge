package dev.surge.booking.hold;

import dev.surge.booking.error.HoldExistsException;
import dev.surge.booking.error.InvalidSeatsException;
import dev.surge.booking.error.SaleNotOpenException;
import dev.surge.booking.error.SeatsTakenException;
import dev.surge.booking.event.SeatState;
import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.inventory.SeatInfo;
import dev.surge.booking.pricing.PriceCalculator;
import dev.surge.booking.pricing.PricingContext;
import dev.surge.booking.pricing.TierPrices;
import dev.surge.booking.stream.SeatEventPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final PriceCalculator calculator;
    private final TierPrices tierPrices;
    private final SeatEventPublisher publisher;
    private final Clock clock;
    private final Duration ttl;

    public HoldService(InventoryCache inventory, HoldStore store, PriceCalculator calculator, TierPrices tierPrices,
                       SeatEventPublisher publisher, Clock clock, @Value("${surge.hold.ttl}") Duration ttl) {
        this.inventory = inventory;
        this.store = store;
        this.calculator = calculator;
        this.tierPrices = tierPrices;
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
        Instant now = clock.instant(); // read once: the sale-open check and the price agree
        if (now.isBefore(inv.saleOpensAt())) {
            throw new SaleNotOpenException(inv.saleOpensAt());
        }

        List<HoldLine> lines = price(inv, seatIds, now);
        return switch (store.tryHold(userId, eventId, lines, ttl)) {
            case HoldResult.Held h -> {
                publisher.publish(eventId, seatIds, SeatState.HELD);
                BigDecimal total = lines.stream().map(HoldLine::priceInr).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2);
                yield new HoldResponse(h.holdId(), eventId, seatIds, h.expiresAt(), total, lines);
            }
            case HoldResult.SeatsTaken t -> throw new SeatsTakenException(t.seatIds());
            case HoldResult.HoldExists e -> throw new HoldExistsException(e.holdId());
        };
    }

    /**
     * Price lock: each seat is priced now (early bird, surge) and the price is stored with the hold, so the amount
     * shown on the Pay button is exactly what checkout charges. Availability counts sold seats only, as before.
     */
    private List<HoldLine> price(EventInventory inv, List<Long> seatIds, Instant now) {
        int available = inv.totalSeats() - store.soldCount(inv.eventId());
        List<HoldLine> lines = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            SeatInfo info = inv.seats().get(seatId);
            BigDecimal price = calculator.price(new PricingContext(info.tier(), tierPrices.base(info.tier()), now,
                    inv.saleOpensAt(), available, inv.totalSeats())).setScale(2);
            lines.add(new HoldLine(seatId, price));
        }
        return lines;
    }
}
