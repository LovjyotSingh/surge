package dev.surge.catalog.messaging;

import dev.surge.common.messaging.OrderConfirmed;
import dev.surge.common.messaging.SalesReset;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies orders.v1 messages exactly once: the processed_message claim and the MySQL updates share one
 * transaction, so a crash leaves neither or both.
 */
@Component
public class SalesHandler {

    static final String CONSUMER = "catalog-orders";
    private static final Logger log = LoggerFactory.getLogger(SalesHandler.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SalesHandler(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return the new seat count, or empty when nothing changed (duplicate or unknown event) */
    @Transactional
    public Optional<SeatsChange> handle(OrderConfirmed m) {
        if (m.messageId() == null || m.occurredAt() == null || m.totalInr() == null || m.totalInr().signum() < 0
                || m.seatIds() == null || m.seatIds().isEmpty()) {
            throw new IllegalArgumentException("invalid OrderConfirmed");
        }
        if (!claim(m.messageId().toString())) {
            log.info("OrderConfirmed {} already processed, skipping", m.messageId());
            return Optional.empty();
        }
        List<String> zone = jdbc.queryForList("SELECT v.zone_id FROM event e JOIN venue v ON v.id = e.venue_id "
                + "WHERE e.id = ?", String.class, m.eventId());
        if (zone.isEmpty()) {
            log.warn("OrderConfirmed {} is for unknown event {}, skipping", m.messageId(), m.eventId());
            return Optional.empty();
        }
        int n = m.seatIds().size();
        LocalDate day = m.occurredAt().atZone(ZoneId.of(zone.get(0))).toLocalDate();
        jdbc.update("UPDATE event SET seats_left = GREATEST(seats_left - ?, 0) WHERE id = ?", n, m.eventId());
        jdbc.update("INSERT INTO sales_daily (event_id, day, tickets_sold, revenue_inr) VALUES (?, ?, ?, ?) AS new "
                + "ON DUPLICATE KEY UPDATE tickets_sold = sales_daily.tickets_sold + new.tickets_sold, "
                + "revenue_inr = sales_daily.revenue_inr + new.revenue_inr", m.eventId(), day, n, m.totalInr());
        Integer left = jdbc.queryForObject("SELECT seats_left FROM event WHERE id = ?", Integer.class, m.eventId());
        return Optional.of(new SeatsChange(m.eventId(), left));
    }

    @Transactional
    public Optional<SeatsChange> handle(SalesReset m) {
        if (m.messageId() == null) {
            throw new IllegalArgumentException("invalid SalesReset");
        }
        if (!claim(m.messageId().toString())) {
            log.info("SalesReset {} already processed, skipping", m.messageId());
            return Optional.empty();
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id = ?", Integer.class, m.eventId()) == 0) {
            log.warn("SalesReset {} is for unknown event {}, skipping", m.messageId(), m.eventId());
            return Optional.empty();
        }
        jdbc.update("UPDATE event SET seats_left = total_seats WHERE id = ?", m.eventId());
        jdbc.update("DELETE FROM sales_daily WHERE event_id = ?", m.eventId());
        Integer left = jdbc.queryForObject("SELECT seats_left FROM event WHERE id = ?", Integer.class, m.eventId());
        return Optional.of(new SeatsChange(m.eventId(), left));
    }

    private boolean claim(String messageId) {
        return jdbc.update("INSERT IGNORE INTO processed_message (message_id, consumer, processed_at) VALUES (?, ?, ?)",
                messageId, CONSUMER, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) == 1;
    }
}
