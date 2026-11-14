package dev.surge.catalog.publish;

import dev.surge.catalog.event.Event;
import dev.surge.catalog.event.EventRepository;
import dev.surge.catalog.event.RowSpecs;
import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.RowSpec;
import dev.surge.outbox.OutboxMessage;
import dev.surge.outbox.OutboxRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one {@code EventScheduled} outbox row for every event that has not been announced yet, and flags the event
 * in the same transaction, so an event is announced exactly once. The outbox relay then ships the rows to
 * {@code catalog.v1}. Runs at startup and after every import.
 */
@Component
public class EventScheduledPublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventScheduledPublisher.class);

    private final EventRepository events;
    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public EventScheduledPublisher(EventRepository events, OutboxRepository outbox, JsonMapper json, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.events = events;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int published = publishPending();
        log.info("Queued {} EventScheduled message(s) for catalog.v1", published);
    }

    /** @return how many events were announced by this call */
    public int publishPending() {
        Integer count = tx.execute(status -> {
            int published = 0;
            for (Event event : events.lockUnpublished()) {
                EventScheduled message;
                try {
                    message = toMessage(event);
                    validate(message);
                } catch (RuntimeException e) {
                    // Booking would reject this message anyway; leave the event unflagged so it shows up in the logs.
                    log.error("Event {} cannot be announced: {}", event.getId(), e.getMessage());
                    continue;
                }
                outbox.save(new OutboxMessage(String.valueOf(event.getId()), MessageTypes.EVENT_SCHEDULED,
                        MessageTypes.CATALOG_DESTINATION, json.writeValueAsString(message), clock.instant()));
                event.markScheduledPublished();
                published++;
            }
            return published;
        });
        return count == null ? 0 : count;
    }

    private EventScheduled toMessage(Event event) {
        List<RowSpec> rows = json.readValue(event.getLayoutJson(), new TypeReference<List<RowSpec>>() { });
        return new EventScheduled(UUID.randomUUID(), event.getId(), event.getTitle(), event.getVenue().getName(),
                event.getVenue().getCity(), event.getVenue().getZoneId(), event.getStartsAt(),
                event.getSaleOpensAt(), rows, clock.instant());
    }

    /** The checks booking's consumer applies; a message that fails them would only end up in its dead-letter topic. */
    static void validate(EventScheduled m) {
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
        RowSpecs.validate(m.rows());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
