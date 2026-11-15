package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.search.EventDocument;
import dev.surge.catalog.search.EventIndexer;
import dev.surge.catalog.support.IntegrationTest;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.OrderConfirmed;
import dev.surge.common.messaging.SalesReset;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The orders.v1 consumer. Each test uses its own event ids and puts the seat counts back afterwards; messages for
 * one event share a key, hence a partition, so a later message being processed proves the earlier ones were too.
 */
@IntegrationTest
class SalesHandlerTest {

    private static final Instant EVENING = Instant.parse("2026-12-01T20:00:00Z");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaContainer kafkaContainer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired ElasticsearchOperations elasticsearch;
    @Autowired EventIndexer indexer;

    @AfterEach
    void restoreSeats() {
        jdbc.update("UPDATE event SET seats_left = total_seats WHERE id IN (2, 4, 20, 21, 22, 23, 24)");
        jdbc.update("DELETE FROM sales_daily WHERE event_id IN (2, 4, 20, 21, 22, 23, 24)");
        indexer.reindexAll();
    }

    // ---- helpers ----

    private void send(long eventId, String type, Object message) {
        send(eventId, type, json.writeValueAsString(message));
    }

    private void send(long eventId, String type, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(MessageTypes.ORDERS_TOPIC, String.valueOf(eventId),
                payload);
        record.headers().add("type", type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private static OrderConfirmed order(UUID id, long eventId, int seats, String total, Instant at) {
        List<Long> seatIds = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            seatIds.add(1000L + i);
        }
        return new OrderConfirmed(id, 77, "u-1", eventId, seatIds, new BigDecimal(total), at);
    }

    private int seatsLeft(long eventId) {
        return jdbc.queryForObject("SELECT seats_left FROM event WHERE id = ?", Integer.class, eventId);
    }

    private int totalSeats(long eventId) {
        return jdbc.queryForObject("SELECT total_seats FROM event WHERE id = ?", Integer.class, eventId);
    }

    private boolean processed(UUID messageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ? "
                + "AND consumer = 'catalog-orders'", Integer.class, messageId.toString()) == 1;
    }

    private void awaitProcessed(UUID messageId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(processed(messageId)).isTrue());
    }

    private ZoneId zoneOf(long eventId) {
        return ZoneId.of(jdbc.queryForObject("SELECT v.zone_id FROM event e JOIN venue v ON v.id = e.venue_id "
                + "WHERE e.id = ?", String.class, eventId));
    }

    private int esSeatsLeft(long eventId) {
        EventDocument doc = elasticsearch.get(String.valueOf(eventId), EventDocument.class);
        assertThat(doc).isNotNull();
        return doc.seatsLeft();
    }

    /** Reads orders.v1.catalog.DLT from the start until a record with this payload shows up (or 30 s pass). */
    private ConsumerRecord<String, String> findInDeadLetterTopic(String payload) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "catalog-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of("orders.v1.catalog.DLT"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (payload.equals(r.value())) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    // ---- OrderConfirmed ----

    @Test
    void orderConfirmedDecrementsSeatsLeft() throws Exception {
        long eventId = 20;
        int before = seatsLeft(eventId);
        UUID id = UUID.randomUUID();

        send(eventId, MessageTypes.ORDER_CONFIRMED, order(id, eventId, 3, "7497.00", EVENING));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(seatsLeft(eventId)).isEqualTo(before - 3);
            assertThat(esSeatsLeft(eventId)).isEqualTo(before - 3);
        });
        assertThat(processed(id)).isTrue();
        // the daily sales row is on the date in the event's own zone, not in UTC
        LocalDate expected = EVENING.atZone(zoneOf(eventId)).toLocalDate();
        assertThat(expected).isNotEqualTo(EVENING.atZone(ZoneId.of("UTC")).toLocalDate());
        Map<String, Object> row = jdbc.queryForMap("SELECT day, tickets_sold, revenue_inr FROM sales_daily "
                + "WHERE event_id = ?", eventId);
        assertThat(row.get("day").toString()).isEqualTo(expected.toString());
        assertThat(((Number) row.get("tickets_sold")).intValue()).isEqualTo(3);
        assertThat((BigDecimal) row.get("revenue_inr")).isEqualByComparingTo("7497.00");
        // and a search sees the new count (within the index refresh interval)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get("/api/catalog/events/search")
                        .param("q", jdbc.queryForObject("SELECT title FROM event WHERE id = ?", String.class, eventId))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id==" + eventId + ")].seatsLeft").value(before - 3)));
    }


    @Test
    void secondOrderOnTheSameDayAddsUp() throws Exception {
        long eventId = 23;
        int before = seatsLeft(eventId);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        send(eventId, MessageTypes.ORDER_CONFIRMED, order(a, eventId, 2, "3000.00", EVENING));
        send(eventId, MessageTypes.ORDER_CONFIRMED, order(b, eventId, 1, "1499.50", EVENING.plusSeconds(60)));
        awaitProcessed(b);

        assertThat(seatsLeft(eventId)).isEqualTo(before - 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tickets_sold FROM sales_daily WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT revenue_inr FROM sales_daily WHERE event_id = ?", BigDecimal.class,
                eventId)).isEqualByComparingTo("4499.50");
    }

    @Test
    void seatsLeftNeverGoesBelowZero() {
        long eventId = 4; // 20 seats
        UUID id = UUID.randomUUID();

        send(eventId, MessageTypes.ORDER_CONFIRMED, order(id, eventId, 25, "100.00", EVENING));
        awaitProcessed(id);

        assertThat(seatsLeft(eventId)).isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(esSeatsLeft(eventId)).isZero());
    }

    @Test
    void duplicateMessageIsIgnored() {
        long eventId = 21;
        int before = seatsLeft(eventId);
        UUID id = UUID.randomUUID();
        UUID sentinel = UUID.randomUUID();
        OrderConfirmed order = order(id, eventId, 2, "2998.00", EVENING);

        send(eventId, MessageTypes.ORDER_CONFIRMED, order);
        send(eventId, MessageTypes.ORDER_CONFIRMED, order);
        send(eventId, MessageTypes.ORDER_CONFIRMED, order);
        // same key, same partition, in order: once the sentinel is done the three copies have been consumed
        send(eventId, MessageTypes.ORDER_CONFIRMED, order(sentinel, eventId, 1, "1499.00", EVENING));
        awaitProcessed(sentinel);

        assertThat(seatsLeft(eventId)).isEqualTo(before - 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class,
                id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tickets_sold FROM sales_daily WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT revenue_inr FROM sales_daily WHERE event_id = ?", BigDecimal.class,
                eventId)).isEqualByComparingTo("4497.00");
    }

    @Test
    void unknownEventIsSkippedButRecordedAndDoesNotBlockTheNextMessage() {
        UUID ghost = UUID.randomUUID();
        UUID real = UUID.randomUUID();
        int before = seatsLeft(24);

        send(999_999, MessageTypes.ORDER_CONFIRMED, order(ghost, 999_999, 1, "10.00", EVENING));
        send(24, MessageTypes.ORDER_CONFIRMED, order(real, 24, 1, "1499.00", EVENING));
        awaitProcessed(real);

        assertThat(processed(ghost)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE event_id = 999999", Integer.class))
                .isZero();
        assertThat(seatsLeft(24)).isEqualTo(before - 1);
    }

    @Test
    void unknownTypesAreIgnored() {
        UUID ignored = UUID.randomUUID();
        UUID real = UUID.randomUUID();
        int before = seatsLeft(24);

        send(24, "SomethingElse", order(ignored, 24, 5, "5.00", EVENING));
        send(24, MessageTypes.ORDER_CONFIRMED, order(real, 24, 1, "1499.00", EVENING));
        awaitProcessed(real);

        assertThat(processed(ignored)).isFalse();
        assertThat(seatsLeft(24)).isEqualTo(before - 1);
    }

    // ---- SalesReset ----

    @Test
    void salesResetRestoresSeatsLeft() {
        long eventId = 22;
        int total = totalSeats(eventId);
        UUID order = UUID.randomUUID();
        UUID reset = UUID.randomUUID();
        send(eventId, MessageTypes.ORDER_CONFIRMED, order(order, eventId, 4, "5996.00", EVENING));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(seatsLeft(eventId)).isEqualTo(total - 4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);

        send(eventId, MessageTypes.SALES_RESET, new SalesReset(reset, eventId, EVENING.plusSeconds(600)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(seatsLeft(eventId)).isEqualTo(total);
            assertThat(esSeatsLeft(eventId)).isEqualTo(total);
        });
        assertThat(processed(reset)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_daily WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    // ---- stats ----

    @Test
    void dailyStatsAggregates() throws Exception {
        long eventId = 2; // Dubai, UTC+4
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // 10:00Z and 12:00Z are 14:00 and 16:00 on 1 Dec in Dubai; 21:00Z is 01:00 on 2 Dec
        send(eventId, MessageTypes.ORDER_CONFIRMED,
                order(a, eventId, 2, "5000.00", Instant.parse("2026-12-01T10:00:00Z")));
        send(eventId, MessageTypes.ORDER_CONFIRMED,
                order(b, eventId, 1, "2999.50", Instant.parse("2026-12-01T12:00:00Z")));
        send(eventId, MessageTypes.ORDER_CONFIRMED,
                order(c, eventId, 1, "1499.00", Instant.parse("2026-12-01T21:00:00Z")));
        awaitProcessed(c);

        mvc.perform(get("/api/catalog/admin/stats/daily").header("X-User-Roles", "USER,ADMIN").param("eventId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2026-12-01"))
                .andExpect(jsonPath("$[0].ticketsSold").value(3))
                .andExpect(jsonPath("$[0].revenueInr").value(7999.5))
                .andExpect(jsonPath("$[1].date").value("2026-12-02"))
                .andExpect(jsonPath("$[1].ticketsSold").value(1))
                .andExpect(jsonPath("$[1].revenueInr").value(1499.0));

        // an event with no sales has an empty list; a missing, malformed or unknown id is a client error
        mvc.perform(get("/api/catalog/admin/stats/daily").header("X-User-Roles", "USER,ADMIN").param("eventId", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/catalog/admin/stats/daily").header("X-User-Roles", "USER,ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        mvc.perform(get("/api/catalog/admin/stats/daily").header("X-User-Roles", "USER,ADMIN").param("eventId", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/admin/stats/daily").header("X-User-Roles", "USER,ADMIN").param("eventId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void dailyStatsNeedTheAdminRoleAndTheOldPublicPathIsGone() throws Exception {
        mvc.perform(get("/api/catalog/admin/stats/daily").param("eventId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        for (String roles : new String[] {"USER", "admin", "SUPERADMIN"}) {
            mvc.perform(get("/api/catalog/admin/stats/daily").param("eventId", "2").header("X-User-Roles", roles))
                    .andExpect(status().isForbidden());
        }
        // authorization runs before argument binding: a non-admin with a bad or missing eventId still gets 403
        mvc.perform(get("/api/catalog/admin/stats/daily").param("eventId", "abc")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/admin/stats/daily")).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/stats/daily").param("eventId", "2").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    // ---- poison messages ----

    @Test
    void malformedMessageGoesToCatalogDeadLetterTopic() {
        String garbage = "{this is not json " + UUID.randomUUID();
        UUID next = UUID.randomUUID();
        int before = seatsLeft(24);

        send(24, MessageTypes.ORDER_CONFIRMED, garbage);
        send(24, MessageTypes.ORDER_CONFIRMED, order(next, 24, 1, "1499.00", EVENING));

        ConsumerRecord<String, String> dead = findInDeadLetterTopic(garbage);
        assertThat(dead).as("record on orders.v1.catalog.DLT").isNotNull();
        assertThat(dead.key()).isEqualTo("24");
        assertThat(dead.topic()).isEqualTo("orders.v1.catalog.DLT");
        // it never blocks the messages behind it
        awaitProcessed(next);
        assertThat(seatsLeft(24)).isEqualTo(before - 1);
    }

    @Test
    void validJsonThatMakesNoSenseAlsoGoesStraightToTheDeadLetterTopic() {
        String noSeats = "{\"messageId\":\"" + UUID.randomUUID() + "\",\"orderId\":1,\"userId\":\"u\",\"eventId\":24,"
                + "\"seatIds\":[],\"totalInr\":10.00,\"occurredAt\":\"2026-12-01T20:00:00Z\"}";
        String noId = "{\"orderId\":1,\"userId\":\"" + UUID.randomUUID() + "\",\"eventId\":24,\"seatIds\":[1],"
                + "\"totalInr\":10.00,\"occurredAt\":\"2026-12-01T20:00:00Z\"}";

        send(24, MessageTypes.ORDER_CONFIRMED, noSeats);
        send(24, MessageTypes.ORDER_CONFIRMED, noId);

        assertThat(findInDeadLetterTopic(noSeats)).isNotNull();
        assertThat(findInDeadLetterTopic(noId)).isNotNull();
    }

    // ---- Elasticsearch trouble must not matter to MySQL ----

    @Test
    void updatingAMissingSearchDocumentIsLoggedNotThrown() {
        indexer.updateSeatsLeft(888_888L, 5);
    }
}
