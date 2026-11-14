package dev.surge.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import dev.surge.outbox.OutboxRelay;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
class OutboxBrokerPublishingTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelay relay;
    @Autowired KafkaContainer kafka;
    /** The type-level spy from @IntegrationTest, delegating to the real template. */
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
        jdbc.update("DELETE FROM outbox");
    }

    private KafkaConsumer<String, String> consumerAtEnd() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        List<TopicPartition> partitions = consumer.partitionsFor("orders.v1").stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // forces the lazy seek to resolve before the test publishes
        return consumer;
    }

    private List<ConsumerRecord<String, String>> poll(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> got = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (got.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(got::add);
        }
        return got;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private long buyOneSeat() throws Exception {
        MvcResult hold = mvc.perform(post("/api/bookings/holds").header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isCreated()).andReturn();
        String holdId = JsonPath.read(hold.getResponse().getContentAsString(), "$.holdId");
        mvc.perform(post("/api/bookings/orders").header("X-User-Id", "u1").header("Idempotency-Key", "k-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + holdId + "\"}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM orders WHERE event_id = 1", Long.class);
    }

    private long unpublished() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Long.class);
    }

    @Test
    void checkoutPublishesOrderConfirmedToKafka() throws Exception {
        try (var consumer = consumerAtEnd()) {
            long orderId = buyOneSeat();

            assertThat(relay.relayBatch()).isEqualTo(2);

            var records = poll(consumer, 1);
            assertThat(records).hasSize(1);
            var record = records.getFirst();
            assertThat(record.key()).isEqualTo("1");
            assertThat(header(record, "type")).isEqualTo("OrderConfirmed");
            assertThat(header(record, "messageId")).isEqualTo(JsonPath.read(record.value(), "$.messageId"));
            assertThat(JsonPath.<Number>read(record.value(), "$.orderId").longValue()).isEqualTo(orderId);
            assertThat(unpublished()).isZero();
        }
    }

    @Test
    void checkoutPublishesTicketRequestedToRabbit() throws Exception {
        Queue queue = new Queue("test.ticket.requested." + UUID.randomUUID(), true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE, "surge.tickets",
                "ticket.requested", null));
        try {
            long orderId = buyOneSeat();

            assertThat(relay.relayBatch()).isEqualTo(2);

            Message message = rabbitTemplate.receive(queue.getName(), WAIT.toMillis());
            assertThat(message).isNotNull();
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat((String) message.getMessageProperties().getHeader("type")).isEqualTo("TicketRequested");
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            assertThat(JsonPath.<Number>read(body, "$.orderId").longValue()).isEqualTo(orderId);
            assertThat(unpublished()).isZero();
        } finally {
            rabbitAdmin.deleteQueue(queue.getName());
        }
    }

    @Test
    void brokerDownStopsBatchAndKeepsRowsUnpublished() throws Exception {
        try (var consumer = consumerAtEnd()) {
            buyOneSeat();
            doThrow(new KafkaException("broker down")).doCallRealMethod()
                    .when(kafkaTemplate).send(any(ProducerRecord.class));

            assertThat(relay.relayBatch()).isZero();
            assertThat(unpublished()).isEqualTo(2);
            assertThat(consumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();

            assertThat(relay.relayBatch()).isEqualTo(2);
            assertThat(unpublished()).isZero();
            assertThat(poll(consumer, 1)).hasSize(1);
        }
    }

    @Test
    void resetPublishesSalesReset() throws Exception {
        try (var consumer = consumerAtEnd()) {
            mvc.perform(post("/api/bookings/admin/events/1/reset")).andExpect(status().isNoContent());

            assertThat(relay.relayBatch()).isEqualTo(1);

            var records = poll(consumer, 1);
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().key()).isEqualTo("1");
            assertThat(header(records.getFirst(), "type")).isEqualTo("SalesReset");
            assertThat(JsonPath.<Number>read(records.getFirst().value(), "$.eventId").longValue()).isEqualTo(1L);
        }
    }
}
