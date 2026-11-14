package dev.surge.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("surge.outbox.poll-interval=PT1H", "surge.outbox.batch-size=10");

    @Test
    void loggingPublisherIsUsedWhenNoOtherPublisherExists() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxPublisher.class);
            assertThat(ctx).hasSingleBean(LoggingOutboxPublisher.class);
            assertThat(ctx).hasSingleBean(OutboxRelay.class);
            assertThat(ctx).hasSingleBean(OutboxPoller.class);
            assertThat(ctx.getBean(MeterRegistry.class).find("outbox_backlog").gauge()).isNotNull();
        });
    }

    @Test
    void userDefinedPublisherReplacesLoggingOne() {
        runner.withBean(OutboxPublisher.class, () -> message -> { }).run(ctx -> {
            assertThat(ctx).hasSingleBean(OutboxPublisher.class);
            assertThat(ctx).doesNotHaveBean(LoggingOutboxPublisher.class);
            assertThat(ctx).hasSingleBean(OutboxRelay.class);
        });
    }
}
