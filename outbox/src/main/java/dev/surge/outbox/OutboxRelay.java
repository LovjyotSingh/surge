package dev.surge.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** Publishes unpublished outbox rows in id order. Rows are locked with SKIP LOCKED so relays can run concurrently. */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository repository, OutboxPublisher publisher, Clock clock, MeterRegistry registry,
                       @Value("${surge.outbox.batch-size}") int batchSize) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        Gauge.builder("outbox_backlog", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox rows not yet published")
                .register(registry);
    }

    /** Publishes up to batch-size rows; stops at the first failure, committing those already published. */
    @Transactional
    public int relayBatch() {
        List<OutboxMessage> batch = repository.lockUnpublished(batchSize);
        int published = 0;
        for (OutboxMessage message : batch) {
            try {
                publisher.publish(message);
            } catch (RuntimeException e) {
                log.warn("outbox publish failed id={} type={}; stopping batch", message.getId(), message.getType(), e);
                break;
            }
            message.setPublishedAt(clock.instant());
            published++;
        }
        return published;
    }
}
