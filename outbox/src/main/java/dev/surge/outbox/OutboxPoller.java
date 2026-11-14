package dev.surge.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduler entry point. Lives in its own bean so the call to {@link OutboxRelay#relayBatch()} goes through the Spring
 * transaction proxy; a self-invocation inside OutboxRelay would bypass @Transactional.
 */
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRelay relay;

    public OutboxPoller(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${surge.outbox.poll-interval}", initialDelayString = "${surge.outbox.poll-interval}")
    public void poll() {
        try {
            relay.relayBatch();
        } catch (RuntimeException e) {
            log.warn("outbox relay batch failed", e);
        }
    }
}
