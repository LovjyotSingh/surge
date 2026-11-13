package dev.surge.booking.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Phase 1 publisher: logs the message. Later phases add broker publishers routed by destination. */
@Component
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("outbox publish type={} destination={} aggregateId={}",
                message.getType(), message.getDestination(), message.getAggregateId());
    }
}
