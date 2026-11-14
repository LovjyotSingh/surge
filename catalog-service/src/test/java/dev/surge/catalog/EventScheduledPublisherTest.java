package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.catalog.publish.EventScheduledPublisher;
import dev.surge.catalog.support.IntegrationTest;
import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.RowSpec;
import dev.surge.outbox.OutboxRelay;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class EventScheduledPublisherTest {

    private static final int SEEDED = 30;

    @Autowired EventScheduledPublisher publisher;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired KafkaContainer kafka;

    private void drainOutbox() {
        while (relay.relayBatch() > 0) {
            // keep going until nothing is left
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Reads catalog.v1 from the start with a fresh group. The topic is shared with other tests, so callers filter. */
    private List<ConsumerRecord<String, String>> readTopic(int atLeastSeededIds) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "catalog-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(MessageTypes.CATALOG_TOPIC));
            long deadline = System.currentTimeMillis() + 30_000;
            long quietUntil = 0;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
                Set<String> seededKeys = new HashSet<>();
                // Only the seeded ids 1-30 count: events imported by other tests must not end the wait early.
                all.forEach(r -> {
                    long key = Long.parseLong(r.key());
                    if (key >= 1 && key <= SEEDED) {
                        seededKeys.add(r.key());
                    }
                });
                if (seededKeys.size() >= atLeastSeededIds) {
                    // Keep polling for a moment so that a duplicate would show up too.
                    if (quietUntil == 0) {
                        quietUntil = System.currentTimeMillis() + 3_000;
                    } else if (System.currentTimeMillis() > quietUntil) {
                        break;
                    }
                }
            }
        }
        return all;
    }

    @Test
    void everyEventGetsExactlyOneEventScheduledOnCatalogTopic() throws Exception {
        drainOutbox();

        List<ConsumerRecord<String, String>> records = readTopic(SEEDED);

        Map<Long, List<EventScheduled>> byEvent = new TreeMap<>();
        for (ConsumerRecord<String, String> record : records) {
            EventScheduled message = json.readValue(record.value(), EventScheduled.class);
            // key = eventId and header type = EventScheduled, for every record
            assertThat(record.key()).isEqualTo(String.valueOf(message.eventId()));
            Header type = record.headers().lastHeader("type");
            assertThat(new String(type.value(), StandardCharsets.UTF_8)).isEqualTo("EventScheduled");
            if (message.eventId() <= SEEDED) {
                byEvent.computeIfAbsent(message.eventId(), k -> new ArrayList<>()).add(message);
            }
        }
        for (long id = 1; id <= SEEDED; id++) {
            assertThat(byEvent.get(id)).as("messages for event %d", id).hasSize(1);
            assertBookingWouldAccept(byEvent.get(id).get(0));
        }
        assertThat(count("SELECT COUNT(*) FROM event WHERE scheduled_published = FALSE")).isZero();
    }

    @Test
    void firstThreeMessagesCarryBookingsPhaseOneSeed() throws Exception {
        drainOutbox();
        Map<Long, EventScheduled> byId = new TreeMap<>();
        for (ConsumerRecord<String, String> record : readTopic(SEEDED)) {
            EventScheduled message = json.readValue(record.value(), EventScheduled.class);
            byId.put(message.eventId(), message);
        }
        EventScheduled one = byId.get(1L);
        assertThat(one.title()).isEqualTo("Neon Nights Live — Flash Sale");
        assertThat(one.venueName()).isEqualTo("Jawaharlal Nehru Stadium, Delhi");
        assertThat(one.city()).isEqualTo("Delhi");
        assertThat(one.zoneId()).isEqualTo("Asia/Kolkata");
        assertThat(one.saleOpensAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(one.rows()).hasSize(20);
        assertThat(one.rows()).allSatisfy(r -> assertThat(r.seats()).isEqualTo(25));
        assertThat(one.rows().subList(0, 4)).allSatisfy(r -> assertThat(r.tier()).isEqualTo("PLATINUM"));
        assertThat(one.rows().subList(4, 12)).allSatisfy(r -> assertThat(r.tier()).isEqualTo("GOLD"));
        assertThat(one.rows().subList(12, 20)).allSatisfy(r -> assertThat(r.tier()).isEqualTo("SILVER"));
        assertThat(one.rows().get(0).rowLabel()).isEqualTo("A");
        assertThat(one.rows().get(19).rowLabel()).isEqualTo("T");

        for (long id : new long[] {2, 3}) {
            EventScheduled m = byId.get(id);
            assertThat(m.rows()).extracting(RowSpec::rowLabel).containsExactly("A", "B", "C", "D");
            assertThat(m.rows()).extracting(RowSpec::tier).containsExactly("PLATINUM", "GOLD", "SILVER", "SILVER");
            assertThat(m.rows()).allSatisfy(r -> assertThat(r.seats()).isEqualTo(25));
            assertThat(m.saleOpensAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        }
        assertThat(byId.get(2L).title()).isEqualTo("Desert Beats");
        assertThat(byId.get(2L).zoneId()).isEqualTo("Asia/Dubai");
        assertThat(byId.get(3L).title()).isEqualTo("Marina Jazz Night");
        assertThat(byId.get(3L).venueName()).isEqualTo("Esplanade Concert Hall, Singapore");
        assertThat(byId.get(3L).zoneId()).isEqualTo("Asia/Singapore");
    }

    @Test
    void rerunningThePublisherPublishesNothingNew() {
        publisher.publishPending();
        int outboxBefore = count("SELECT COUNT(*) FROM outbox");

        assertThat(publisher.publishPending()).isZero();
        assertThat(publisher.publishPending()).isZero();

        assertThat(count("SELECT COUNT(*) FROM outbox")).isEqualTo(outboxBefore);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE type = 'EventScheduled' "
                + "AND destination = 'kafka:catalog.v1'")).isEqualTo(outboxBefore);
    }

    @Test
    void anUnpublishedEventIsPublishedOnceAndFlagged() {
        publisher.publishPending();
        // A copy of event 7 under a new id, so the seeded events keep exactly one message each.
        jdbc.update("DELETE FROM outbox WHERE aggregate_id = '900'");
        jdbc.update("DELETE FROM event WHERE id = 900");
        jdbc.update("INSERT INTO event (id, title, artist, category, venue_id, starts_at, sale_opens_at, description, "
                + "layout_json, min_price_inr, max_price_inr, total_seats, seats_left, scheduled_published) "
                + "SELECT 900, title, artist, category, venue_id, starts_at, sale_opens_at, description, layout_json, "
                + "min_price_inr, max_price_inr, total_seats, seats_left, FALSE FROM event WHERE id = 7");
        int before = count("SELECT COUNT(*) FROM outbox WHERE aggregate_id = '900'");

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(publisher.publishPending()).isZero();

        assertThat(count("SELECT COUNT(*) FROM outbox WHERE aggregate_id = '900'")).isEqualTo(before + 1);
        assertThat(count("SELECT scheduled_published FROM event WHERE id = 900")).isEqualTo(1);
    }

    /** The rules booking's EventScheduledHandler applies before it creates seats; anything we publish must pass. */
    private static void assertBookingWouldAccept(EventScheduled m) {
        assertThat(m.messageId()).isNotNull();
        assertThat(m.title()).isNotBlank();
        assertThat(m.venueName()).isNotBlank();
        assertThat(m.zoneId()).isNotBlank();
        assertThat(ZoneId.of(m.zoneId())).isNotNull();
        assertThat(m.saleOpensAt()).isNotNull();
        assertThat(m.rows()).isNotEmpty();
        Set<String> labels = new HashSet<>();
        for (RowSpec row : m.rows()) {
            assertThat(row.rowLabel()).isNotBlank().hasSizeLessThanOrEqualTo(10);
            assertThat(labels.add(row.rowLabel())).as("unique label %s", row.rowLabel()).isTrue();
            assertThat(row.seats()).isBetween(1, 100);
            assertThat(row.tier()).isIn("PLATINUM", "GOLD", "SILVER");
        }
    }
}
