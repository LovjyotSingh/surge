package dev.surge.outbox.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class OutboxDestinationTest {

    @Test
    void parsesKafkaTopic() {
        assertThat(OutboxDestination.parse("kafka:orders.v1")).isEqualTo(new OutboxDestination.Kafka("orders.v1"));
    }

    @Test
    void parsesRabbitExchangeAndRoutingKey() {
        assertThat(OutboxDestination.parse("rabbit:surge.tickets/ticket.requested"))
                .isEqualTo(new OutboxDestination.Rabbit("surge.tickets", "ticket.requested"));
    }

    @Test
    void routingKeyMayContainSlashes() {
        assertThat(OutboxDestination.parse("rabbit:ex/a/b")).isEqualTo(new OutboxDestination.Rabbit("ex", "a/b"));
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[] {null, "", "tickets", "test.topic", "kafka:", "kafka: ", "rabbit:",
                "rabbit:tickets", "rabbit:/key", "rabbit:ex/", "sqs:queue"}) {
            assertThatIllegalArgumentException().as("destination %s", bad)
                    .isThrownBy(() -> OutboxDestination.parse(bad));
        }
    }
}
