package dev.surge.outbox.routing;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.surge.outbox.OutboxMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoutingOutboxPublisherTest {

    private static OutboxMessage message(String destination) {
        return new OutboxMessage("1", "T", destination, "{}", Instant.now());
    }

    @Test
    void routesByDestinationPrefix() {
        OutboxSender kafka = mock(OutboxSender.class);
        OutboxSender rabbit = mock(OutboxSender.class);
        var publisher = new RoutingOutboxPublisher(kafka, rabbit);

        publisher.publish(message("kafka:t"));
        publisher.publish(message("rabbit:e/k"));

        verify(kafka).send(any(), any(OutboxDestination.Kafka.class));
        verify(rabbit).send(any(), any(OutboxDestination.Rabbit.class));
    }

    @Test
    void missingSenderThrowsInsteadOfDropping() {
        OutboxSender kafka = mock(OutboxSender.class);
        var publisher = new RoutingOutboxPublisher(kafka, null);

        assertThatIllegalStateException().isThrownBy(() -> publisher.publish(message("rabbit:e/k")))
                .withMessageContaining("rabbit");
        verifyNoInteractions(kafka);
        var noKafka = new RoutingOutboxPublisher(null, mock(OutboxSender.class));
        assertThatIllegalStateException().isThrownBy(() -> noKafka.publish(message("kafka:t")))
                .withMessageContaining("kafka");
    }

    @Test
    void unknownDestinationThrows() {
        var publisher = new RoutingOutboxPublisher(mock(OutboxSender.class), mock(OutboxSender.class));
        assertThatIllegalArgumentException().isThrownBy(() -> publisher.publish(message("test.topic")));
    }
}
