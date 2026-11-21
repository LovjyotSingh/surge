package dev.surge.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.core.exc.StreamReadException;

/** seatsLeft drift: a transient failure is retried for about 15 s before dead-lettering; poison goes at once. */
class KafkaErrorHandlingConfigTest {

    private final List<Long> waits = new ArrayList<>();
    private final List<ConsumerRecord<?, ?>> deadLettered = new ArrayList<>();
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.v1", 0, 7L, "24", "{}");

    private DefaultErrorHandler handler() {
        return KafkaErrorHandlingConfig.errorHandler((r, e) -> deadLettered.add(r), new BackOffHandler() {
            @Override
            public void onNextBackOff(MessageListenerContainer container, Exception exception, long nextBackOff) {
                waits.add(nextBackOff);
            }
        });
    }

    @Test
    void theBackOffIsOneTwoFourEightSecondsThenStops() {
        BackOffExecution execution = KafkaErrorHandlingConfig.retryBackOff().start();
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        assertThat(execution.nextBackOff()).isEqualTo(2000L);
        assertThat(execution.nextBackOff()).isEqualTo(4000L);
        assertThat(execution.nextBackOff()).isEqualTo(8000L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void aTransientDatabaseErrorIsRetriedFiveTimesInAllBeforeItIsDeadLettered() {
        DefaultErrorHandler handler = handler();
        for (int attempt = 1; attempt <= 4; attempt++) {
            boolean handled = handler.handleOne(new TransientDataAccessResourceException("db restarting"), record,
                    consumer, container);
            assertThat(handled).as("attempt %d is retried", attempt).isFalse();
            assertThat(deadLettered).isEmpty();
        }
        boolean handled = handler.handleOne(new TransientDataAccessResourceException("db restarting"), record, consumer,
                container);
        assertThat(handled).as("the fifth failure gives up").isTrue();
        assertThat(deadLettered).containsExactly(record);
        assertThat(waits).containsExactly(1000L, 2000L, 4000L, 8000L);
    }

    @Test
    void aMalformedOrInvalidPayloadIsDeadLetteredAtOnceWithoutWaiting() {
        for (Exception poison : List.of(new StreamReadException(null, "not json"),
                new IllegalArgumentException("invalid OrderConfirmed"))) {
            deadLettered.clear();
            boolean handled = handler().handleOne(poison, record, consumer, container);
            assertThat(handled).as(poison.getClass().getSimpleName()).isTrue();
            assertThat(deadLettered).containsExactly(record);
        }
        assertThat(waits).isEmpty();
    }
}
