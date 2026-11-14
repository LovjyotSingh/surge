package dev.surge.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.surge.outbox.routing.RoutingOutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxRoutingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxRoutingAutoConfiguration.class, OutboxAutoConfiguration.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("surge.outbox.poll-interval=PT1H", "surge.outbox.batch-size=10");

    @Test
    void withoutBrokerTemplatesTheLoggingPublisherIsUsed() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxPublisher.class).hasSingleBean(LoggingOutboxPublisher.class);
            assertThat(ctx).doesNotHaveBean(RoutingOutboxPublisher.class);
        });
    }

    @Test
    void kafkaTemplateAloneActivatesRoutingAndReplacesLogging() {
        runner.withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class)).run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxPublisher.class).hasSingleBean(RoutingOutboxPublisher.class);
            assertThat(ctx).doesNotHaveBean(LoggingOutboxPublisher.class);
        });
    }

    @Test
    void rabbitTemplateAloneActivatesRouting() {
        runner.withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class)).run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxPublisher.class).hasSingleBean(RoutingOutboxPublisher.class);
            assertThat(ctx).doesNotHaveBean(LoggingOutboxPublisher.class);
        });
    }

    @Test
    void userDefinedPublisherWinsOverRouting() {
        runner.withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(OutboxPublisher.class, () -> message -> { })
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OutboxPublisher.class);
                    assertThat(ctx).doesNotHaveBean(RoutingOutboxPublisher.class);
                    assertThat(ctx).doesNotHaveBean(LoggingOutboxPublisher.class);
                });
    }
}
