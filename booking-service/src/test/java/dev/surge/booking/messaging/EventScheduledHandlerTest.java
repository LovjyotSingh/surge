package dev.surge.booking.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.MessageTypes;
import dev.surge.common.messaging.RowSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class EventScheduledHandlerTest {

    private static final long NEW_EVENT = 40L;
    private static final long MARKER_EVENT = 41L;
    private static final long WAIT_MILLIS = 10_000;
    /** Two retries 1 s apart would add at least 2 s between the original send and the DLT publish. */
    private static final long NO_RETRY_MILLIS = 1_500;

    @Autowired TestData testData;

    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired KafkaContainer kafka;
    /** The type-level spy from @IntegrationTest, delegating to the real template. */
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void reset() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        testData.deleteEvent(NEW_EVENT);
        testData.deleteEvent(MARKER_EVENT);
        jdbc.update("UPDATE event_ref SET title = 'Neon Nights Live — Flash Sale', "
                + "venue_name = 'Jawaharlal Nehru Stadium, Delhi', zone_id = 'Asia/Kolkata' WHERE id = 1");
        testData.setSaleOpensAt(1L, Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("DELETE FROM processed_message WHERE consumer = 'booking-catalog'");
    }

    private EventScheduled scheduled(UUID messageId, long eventId, String title, List<RowSpec> rows) {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");
        return new EventScheduled(messageId, eventId, title, "Test Arena", "Delhi", "Asia/Kolkata",
                now.plusSeconds(86_400 * 30L), now.plusSeconds(3600), rows, now);
    }

    private void publish(EventScheduled message) {
        send(String.valueOf(message.eventId()), json.writeValueAsString(message));
    }

    private void send(String key, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(MessageTypes.CATALOG_TOPIC, key, payload);
        record.headers().add("type", MessageTypes.EVENT_SCHEDULED.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + WAIT_MILLIS + " ms");
            }
            Thread.sleep(100);
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void newEventCreatesSeats() throws Exception {
        publish(scheduled(UUID.randomUUID(), NEW_EVENT, "Fresh Event",
                List.of(new RowSpec("A", 10, "PLATINUM"), new RowSpec("B", 10, "GOLD"))));

        await(() -> count("SELECT COUNT(*) FROM seat WHERE event_id = ?", NEW_EVENT) == 20);

        assertThat(jdbc.queryForObject("SELECT title FROM event_ref WHERE id = ?", String.class, NEW_EVENT))
                .isEqualTo("Fresh Event");
        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = ? AND row_label = 'A' AND tier = 'PLATINUM' "
                + "AND section = 'MAIN' AND status = 'AVAILABLE'", NEW_EVENT)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = ? AND row_label = 'B' AND tier = 'GOLD'",
                NEW_EVENT)).isEqualTo(10);
        assertThat(count("SELECT COUNT(DISTINCT number) FROM seat WHERE event_id = ? AND row_label = 'A'",
                NEW_EVENT)).isEqualTo(10);
    }

    @Test
    void existingEventKeepsSeats() throws Exception {
        publish(scheduled(UUID.randomUUID(), 1L, "Renamed Neon Nights", List.of(new RowSpec("Z", 3, "SILVER"))));

        await(() -> "Renamed Neon Nights".equals(
                jdbc.queryForObject("SELECT title FROM event_ref WHERE id = 1", String.class)));

        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = 1")).isEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = 1 AND row_label = 'Z'")).isZero();
        assertThat(jdbc.queryForObject("SELECT venue_name FROM event_ref WHERE id = 1", String.class))
                .isEqualTo("Test Arena");
    }

    @Test
    void duplicateMessageIsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        String key = String.valueOf(NEW_EVENT);
        EventScheduled message = scheduled(id, NEW_EVENT, "Once Only", List.of(new RowSpec("A", 5, "SILVER")));
        send(key, json.writeValueAsString(message));
        send(key, json.writeValueAsString(message));
        // The marker uses the SAME record key (same partition), so it is consumed after both duplicates.
        send(key, json.writeValueAsString(
                scheduled(UUID.randomUUID(), MARKER_EVENT, "Marker", List.of(new RowSpec("A", 2, "GOLD")))));

        await(() -> count("SELECT COUNT(*) FROM event_ref WHERE id = ?", MARKER_EVENT) == 1);

        assertThat(count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", id.toString())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = ?", NEW_EVENT)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM event_ref WHERE id = ?", NEW_EVENT)).isEqualTo(1);
    }

    @Test
    void malformedMessageGoesToDlt() {
        String garbage = "{this is not json " + UUID.randomUUID();
        send("garbage", garbage);

        assertThat(awaitDltWithoutRetry(garbage)).isTrue();
    }

    @Test
    void emptyLayoutGoesToDltAndCreatesNothing() {
        assertRejected(scheduled(UUID.randomUUID(), NEW_EVENT, "No Rows", List.of()));
    }

    @Test
    void duplicateRowLabelGoesToDltAndCreatesNothing() {
        assertRejected(scheduled(UUID.randomUUID(), NEW_EVENT, "Dup Rows",
                List.of(new RowSpec("A", 3, "GOLD"), new RowSpec("A", 3, "SILVER"))));
    }

    @Test
    void unknownTierGoesToDltAndCreatesNothing() {
        assertRejected(scheduled(UUID.randomUUID(), NEW_EVENT, "Bad Tier", List.of(new RowSpec("A", 3, "DIAMOND"))));
    }

    @Test
    void zeroSeatRowGoesToDltAndCreatesNothing() {
        assertRejected(scheduled(UUID.randomUUID(), NEW_EVENT, "Zero Seats", List.of(new RowSpec("A", 0, "GOLD"))));
    }

    private void assertRejected(EventScheduled message) {
        String payload = json.writeValueAsString(message);
        send(String.valueOf(message.eventId()), payload);

        assertThat(awaitDltWithoutRetry(payload)).as("reached the DLT without retries").isTrue();
        assertThat(count("SELECT COUNT(*) FROM event_ref WHERE id = ?", message.eventId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = ?", message.eventId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM processed_message WHERE message_id = ?",
                message.messageId().toString())).isZero();
    }

    /**
     * Waits for the payload on catalog.v1.DLT. "No retry" is proven from the DLT record itself: Spring stamps the
     * original record's timestamp in the {@code kafka_dlt-original-timestamp} header, and two retries 1 s apart
     * would put the DLT record at least 2 s after it. So the gap must stay under {@link #NO_RETRY_MILLIS}.
     */
    private boolean awaitDltWithoutRetry(String payload) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(MessageTypes.CATALOG_TOPIC + ".DLT"));
            long deadline = System.currentTimeMillis() + WAIT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (payload.equals(r.value())) {
                        Header original = r.headers().lastHeader("kafka_dlt-original-timestamp");
                        assertThat(original).as("original-timestamp header").isNotNull();
                        long gap = r.timestamp() - ByteBuffer.wrap(original.value()).getLong();
                        assertThat(gap).as("send-to-DLT gap in ms").isLessThan(NO_RETRY_MILLIS);
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
