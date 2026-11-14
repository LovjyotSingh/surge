package dev.surge.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the transactional outbox into any service that depends on this module. {@code @AutoConfigurationPackage}
 * adds {@code dev.surge.outbox} to Boot's default entity and repository scan packages, so the application's own
 * packages keep being scanned as well. The application must provide a {@link Clock} and a {@link MeterRegistry}
 * and set {@code surge.outbox.poll-interval} and {@code surge.outbox.batch-size}.
 */
@AutoConfiguration
@AutoConfigurationPackage(basePackages = "dev.surge.outbox")
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxPublisher.class)
    LoggingOutboxPublisher loggingOutboxPublisher() {
        return new LoggingOutboxPublisher();
    }

    @Bean
    OutboxRelay outboxRelay(OutboxRepository repository, OutboxPublisher publisher, Clock clock,
                            MeterRegistry registry, @Value("${surge.outbox.batch-size}") int batchSize) {
        return new OutboxRelay(repository, publisher, clock, registry, batchSize);
    }

    @Bean
    OutboxPoller outboxPoller(OutboxRelay relay) {
        return new OutboxPoller(relay);
    }
}
