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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private static final int MAX_LABEL = 10;
    private static final int MAX_SEATS_PER_ROW = 100;
    /** SEC-29: catalog's own limit (RowSpecs.MAX_ROWS), so at most 10,000 seats per event. */
    private static final int MAX_ROWS = 100;
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
        validate(message);
        if (processed.claim(message.messageId().toString(), CONSUMER, clock.instant()) == 0) {
            log.info("EventScheduled {} already processed, skipping", message.messageId());
            return;
        }
        long eventId = message.eventId();
        Optional<EventRef> existing = events.findById(eventId);
        if (existing.isPresent()) {
            refuseSaleMoveAfterSales(existing.get(), message);
            existing.get().updateDetails(message.title(), message.venueName(), message.saleOpensAt(), message.zoneId());
        } else {
            events.saveAndFlush(new EventRef(eventId, message.title(), message.venueName(), message.saleOpensAt(),
                    message.zoneId(), EventStatus.ACTIVE));
            seats.saveAll(seatsFor(eventId, message.rows()));
        }
        evictAfterCommit(eventId);
    }

    /**
     * SEC-06: once seats are sold, the sale time is history. A message that would move it (open a sale early,
     * postpone it, reopen it) is forged or a bug: it goes to the DLT (non-retryable) and the transaction rolls back,
     * the processed-message claim included, so the event keeps all its details. The log names ids only, never the
     * payload. The column holds microseconds, so the comparison does too.
     *
     * <p>The SOLD count is a locking read ({@link SeatRepository#countSoldLocking}), so it cannot miss a checkout
     * committing at the same moment: an uncommitted sale makes it wait and is then counted, and a sale that starts
     * later waits until this transaction has committed (the move then happened before that sale). Between the sale
     * opening and the first committed order only HELD seats exist (holds live in Redis), so this guard cannot stop a
     * forged move in that window; the real control against forged messages is the Kafka ACLs (Task 16: only catalog
     * may write {@code catalog.v1}).
     */
    private void refuseSaleMoveAfterSales(EventRef event, EventScheduled message) {
        Instant stored = event.getSaleOpensAt().truncatedTo(ChronoUnit.MICROS);
        if (stored.equals(message.saleOpensAt().truncatedTo(ChronoUnit.MICROS))) {
            return;
        }
        if (seats.countSoldLocking(event.getId()) > 0) {
            log.warn("EventScheduled {} would move the sale of event {}, which has sold seats; sent to the DLT",
                    message.messageId(), event.getId());
            throw new SaleMoveRefusedException(event.getId());
        }
    }

    /** Not retryable: an {@link IllegalArgumentException}, so the error handler dead-letters it at once. */
    static final class SaleMoveRefusedException extends IllegalArgumentException {

        SaleMoveRefusedException(long eventId) {
            super("event " + eventId + " has sold seats; its sale time cannot move");
        }
    }

    /** Rejects a message that can never succeed, before any write. Failures are non-retryable (straight to the DLT). */
    static void validate(EventScheduled m) {
        if (m.messageId() == null) {
            throw new IllegalArgumentException("EventScheduled has no messageId");
        }
        requireText(m.title(), "title");
        requireText(m.venueName(), "venueName");
        requireText(m.zoneId(), "zoneId");
        try {
            ZoneId.of(m.zoneId());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid zoneId: " + m.zoneId(), e);
        }
        if (m.saleOpensAt() == null) {
            throw new IllegalArgumentException("saleOpensAt is required");
        }
        if (m.rows() == null || m.rows().isEmpty()) {
            throw new IllegalArgumentException("rows must not be empty");
        }
        if (m.rows().size() > MAX_ROWS) {
            throw new IllegalArgumentException("at most " + MAX_ROWS + " rows are allowed");
        }
        Set<String> labels = new HashSet<>();
        for (RowSpec row : m.rows()) {
            if (row == null) {
                throw new IllegalArgumentException("null row");
            }
            if (row.rowLabel() == null || row.rowLabel().isBlank() || row.rowLabel().length() > MAX_LABEL) {
                throw new IllegalArgumentException("rowLabel must be 1-" + MAX_LABEL + " characters");
            }
            if (!labels.add(row.rowLabel())) {
                throw new IllegalArgumentException("duplicate rowLabel " + row.rowLabel());
            }
            if (row.seats() < 1 || row.seats() > MAX_SEATS_PER_ROW) {
                throw new IllegalArgumentException("seats in row " + row.rowLabel() + " must be 1-" + MAX_SEATS_PER_ROW);
            }
            if (row.tier() == null) {
                throw new IllegalArgumentException("tier is required in row " + row.rowLabel());
            }
            Tier.valueOf(row.tier());
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static List<Seat> seatsFor(long eventId, List<RowSpec> rows) {
        List<Seat> result = new ArrayList<>();
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
