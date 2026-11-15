package dev.surge.notification.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.messaging.MessageTypes;
import dev.surge.notification.support.Identity;
import dev.surge.notification.support.IntegrationTest;
import dev.surge.notification.support.TestcontainersConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit consumer. Messages of one test share a key (so a partition, so an order): a later marker message being
 * logged proves the earlier ones were handled. Every test uses its own message ids, and none leaves a message in
 * flight when it ends.
 */
@IntegrationTest
class OrdersAuditListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DLT = "orders.v1.audit.DLT";

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaContainer kafkaContainer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AuditLogWriter writer;

    @BeforeEach
    void emptyAuditDirectory() throws IOException {
        try (Stream<Path> files = Files.list(TestcontainersConfig.AUDIT_DIR)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String order(UUID id) {
        return "{\"messageId\":\"" + id + "\",\"orderId\":1,\"userId\":\"u-1\",\"eventId\":3,\"seatIds\":[1],"
                + "\"totalInr\":10.00,\"occurredAt\":\"2026-12-01T20:00:00Z\"}";
    }

    private void send(String key, String type, UUID idHeader, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(MessageTypes.ORDERS_TOPIC, key, payload);
        if (type != null) {
            record.headers().add("type", type.getBytes(StandardCharsets.UTF_8));
        }
        if (idHeader != null) {
            record.headers().add("messageId", idHeader.toString().getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).join();
    }

    private List<JsonNode> allLines() {
        List<JsonNode> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(TestcontainersConfig.AUDIT_DIR)) {
            for (Path file : files.toList()) {
                for (String line : Files.readAllLines(file)) {
                    out.add(JSON.readTree(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private List<JsonNode> linesFor(UUID id) {
        return allLines().stream().filter(l -> id.toString().equals(l.get("payload").path("messageId").asString()))
                .toList();
    }

    private void awaitLogged(UUID id) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(linesFor(id)).isNotEmpty());
    }

    private ConsumerRecord<String, String> findInDeadLetterTopic(String payload) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "audit-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(DLT));
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

    private static MockHttpServletRequestBuilder adminGet(String url) {
        return get(url).with(Identity.admin());
    }

    @Test
    void orderConfirmedIsLoggedWithTypeAndRawPayload() throws Exception {
        UUID id = UUID.randomUUID();

        send("3", MessageTypes.ORDER_CONFIRMED, id, order(id));

        awaitLogged(id);
        JsonNode line = linesFor(id).getFirst();
        assertThat(line.get("type").asString()).isEqualTo("OrderConfirmed");
        assertThat(line.get("payload").isObject()).isTrue();
        assertThat(Files.readString(TestcontainersConfig.AUDIT_DIR.resolve("orders-" + writer.today() + ".jsonl")))
                .contains("\"totalInr\":10.00");
        assertThat(line.get("payload").get("orderId").asInt()).isEqualTo(1);
        assertThat(line.get("at").asString()).endsWith("Z");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ? AND consumer = 'audit'",
                Integer.class, id.toString())).isEqualTo(1);
    }

    @Test
    void duplicateMessageIsIgnored() {
        UUID id = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        send("dup", MessageTypes.ORDER_CONFIRMED, id, order(id));
        send("dup", MessageTypes.ORDER_CONFIRMED, id, order(id));
        send("dup", MessageTypes.ORDER_CONFIRMED, marker, order(marker));

        awaitLogged(marker);
        assertThat(linesFor(id)).hasSize(1);
    }

    @Test
    void unknownTypeIsStillLoggedAndMissingTypeIsUnknown() {
        UUID odd = UUID.randomUUID();
        UUID untyped = UUID.randomUUID();

        send("odd", "SomethingNew", odd, "{\"messageId\":\"" + odd + "\",\"x\":1}");
        send("odd", null, untyped, "{\"messageId\":\"" + untyped + "\"}");

        awaitLogged(odd);
        awaitLogged(untyped);
        assertThat(linesFor(odd).getFirst().get("type").asString()).isEqualTo("SomethingNew");
        assertThat(linesFor(untyped).getFirst().get("type").asString()).isEqualTo("unknown");
    }

    @Test
    void messageIdFallsBackToThePayloadWhenTheHeaderIsMissing() {
        UUID id = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        send("fb", MessageTypes.SALES_RESET, null, "{\"messageId\":\"" + id + "\",\"eventId\":3}");
        send("fb", MessageTypes.SALES_RESET, null, "{\"messageId\":\"" + id + "\",\"eventId\":3}");
        send("fb", MessageTypes.SALES_RESET, marker, "{\"messageId\":\"" + marker + "\",\"eventId\":3}");

        awaitLogged(marker);
        assertThat(linesFor(id)).hasSize(1);
    }

    @Test
    void malformedPayloadGoesToTheDeadLetterTopicAndWritesNoLine() {
        UUID id = UUID.randomUUID();
        String garbage = "{this is not json " + id;
        UUID next = UUID.randomUUID();

        send("bad", MessageTypes.ORDER_CONFIRMED, id, garbage);
        send("bad", MessageTypes.ORDER_CONFIRMED, next, order(next));

        ConsumerRecord<String, String> dead = findInDeadLetterTopic(garbage);
        assertThat(dead).as("record on " + DLT).isNotNull();
        assertThat(dead.topic()).isEqualTo(DLT);
        assertThat(dead.key()).isEqualTo("bad");
        awaitLogged(next); // it never blocks the messages behind it
        assertThat(allLines()).noneMatch(l -> l.toString().contains(id.toString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ? AND consumer = 'audit'",
                Integer.class, id.toString())).isZero();
    }

    @Test
    void messageWithNoIdAnywhereGoesToTheDeadLetterTopic() {
        String noId = "{\"orderId\":1,\"note\":\"" + UUID.randomUUID() + "\"}";
        UUID next = UUID.randomUUID();

        send("noid", MessageTypes.ORDER_CONFIRMED, null, noId);
        send("noid", MessageTypes.ORDER_CONFIRMED, next, order(next));

        assertThat(findInDeadLetterTopic(noId)).isNotNull();
        awaitLogged(next);
        assertThat(allLines()).noneMatch(l -> l.toString().contains("\"note\""));
    }

    @Test
    void adminTailReturnsTheLastLinesAndTheTotal() throws Exception {
        for (int i = 1; i <= 5; i++) {
            writer.append("T" + i, "{\"n\":" + i + "}");
        }
        String today = writer.today().toString();

        mvc.perform(adminGet("/api/notifications/admin/audit?date=" + today + "&tail=2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.date").value(today))
                .andExpect(jsonPath("$.lines").value(5))
                .andExpect(jsonPath("$.tail.length()").value(2))
                .andExpect(jsonPath("$.tail[0].type").value("T4"))
                .andExpect(jsonPath("$.tail[1].payload.n").value(5));
        // defaults: today, 50
        mvc.perform(adminGet("/api/notifications/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today))
                .andExpect(jsonPath("$.tail.length()").value(5));
    }

    @Test
    void adminTailOfAMissingDayIsEmpty() throws Exception {
        mvc.perform(adminGet("/api/notifications/admin/audit?date=2020-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2020-01-01"))
                .andExpect(jsonPath("$.lines").value(0))
                .andExpect(jsonPath("$.tail.length()").value(0));
    }

    @Test
    void adminAuditNeedsAUserAndTheAdminRole() throws Exception {
        // the gateway's anonymous token is no user, whatever legacy headers come with it
        mvc.perform(get("/api/notifications/admin/audit").with(Identity.anonymous()).header("X-User-Id", "u-1")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        // a lower-case "admin" cannot even be signed: the gateway drops such roles
        for (String[] roles : new String[][] {{}, {"USER"}, {"SUPERADMIN"}, {"XADMIN", "USER"}}) {
            mvc.perform(get("/api/notifications/admin/audit").with(Identity.userWithRoles("u-1", roles)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        }
        mvc.perform(get("/api/notifications/admin/audit").with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void adminAuditRejectsBadDateAndTail() throws Exception {
        String[] bad = {"date=2026-13-45", "date=yesterday", "date=..%2F..%2Fetc%2Fpasswd", "date=", "tail=0",
                "tail=501", "tail=abc", "tail=-1"};
        for (String query : bad) {
            mvc.perform(adminGet("/api/notifications/admin/audit?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        }
        mvc.perform(adminGet("/api/notifications/admin/audit?tail=500")).andExpect(status().isOk());
        mvc.perform(adminGet("/api/notifications/admin/audit?tail=1")).andExpect(status().isOk());
        // the role check comes before parameter validation
        mvc.perform(get("/api/notifications/admin/audit?tail=0").with(Identity.user("u-1")))
                .andExpect(status().isForbidden());
    }
}
