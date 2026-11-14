package dev.surge.booking.messaging;

import dev.surge.booking.event.EventRef;
import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.event.EventStatus;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.event.Tier;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.RowSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies an {@code EventScheduled} message exactly once: creates the event and its seats, or updates its details. */
@Component
public class EventScheduledHandler {

    static final String CONSUMER = "booking-catalog";
    private static final String SECTION = "MAIN";
    private static final Logger log = LoggerFactory.getLogger(EventScheduledHandler.class);

    private final ProcessedMessageRepository processed;
    private final EventRefRepository events;
    private final SeatRepository seats;
    private final InventoryCache inventory;
    private final Clock clock;

    public EventScheduledHandler(ProcessedMessageRepository processed, EventRefRepository events,
                                 SeatRepository seats, InventoryCache inventory, Clock clock) {
        this.processed = processed;
        this.events = events;
        this.seats = seats;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional
    public void handle(EventScheduled message) {
        if (message.messageId() == null) {
            throw new IllegalArgumentException("EventScheduled has no messageId");
        }
        if (processed.claim(message.messageId().toString(), CONSUMER, clock.instant()) == 0) {
            log.info("EventScheduled {} already processed, skipping", message.messageId());
            return;
        }
        long eventId = message.eventId();
        Optional<EventRef> existing = events.findById(eventId);
        if (existing.isPresent()) {
            existing.get().updateDetails(message.title(), message.venueName(), message.saleOpensAt(), message.zoneId());
        } else {
            events.saveAndFlush(new EventRef(eventId, message.title(), message.venueName(), message.saleOpensAt(),
                    message.zoneId(), EventStatus.ACTIVE));
            seats.saveAll(seatsFor(eventId, message.rows()));
        }
        evictAfterCommit(eventId);
    }

    private static List<Seat> seatsFor(long eventId, List<RowSpec> rows) {
        List<Seat> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (RowSpec row : rows) {
            Tier tier = Tier.valueOf(row.tier());
            for (int number = 1; number <= row.seats(); number++) {
                result.add(new Seat(eventId, SECTION, row.rowLabel(), number, tier, SeatStatus.AVAILABLE));
            }
        }
        return result;
    }

    private void evictAfterCommit(long eventId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                inventory.evict(eventId);
            }
        });
    }
}
