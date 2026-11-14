package dev.surge.outbox.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.surge.outbox.OutboxMessage;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaOutboxSenderTest {

    @SuppressWarnings("unchecked")
    private String keyFor(String payload) {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        new KafkaOutboxSender(template).send(new OutboxMessage("agg-1", "T", "kafka:t", payload, Instant.now()),
                new OutboxDestination.Kafka("t"));
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue().key();
    }

    @Test
    void scalarEventIdIsTheKey() {
        assertThat(keyFor("{\"eventId\":42}")).isEqualTo("42");
        assertThat(keyFor("{\"eventId\":\"e-1\"}")).isEqualTo("e-1");
    }

    @Test
    void missingOrNonScalarEventIdFallsBackToAggregateId() {
        assertThat(keyFor("{}")).isEqualTo("agg-1");
        assertThat(keyFor("{\"eventId\":null}")).isEqualTo("agg-1");
        assertThat(keyFor("{\"eventId\":{\"a\":1}}")).isEqualTo("agg-1");
        assertThat(keyFor("{\"eventId\":[1,2]}")).isEqualTo("agg-1");
        assertThat(keyFor("not json")).isEqualTo("agg-1");
    }
}
