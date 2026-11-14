package dev.surge.notification.messaging;

import dev.surge.common.messaging.TicketRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code TicketRequested}. Retries, backoff and dead-lettering are configured on the container (see
 * {@link RetryConfig}); this class only hands the message to the workflow and lets any failure propagate.
 */
@Component
public class TicketRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(TicketRequestedListener.class);

    private final TicketWorkflow workflow;

    public TicketRequestedListener(TicketWorkflow workflow) {
        this.workflow = workflow;
    }

    @RabbitListener(queues = TicketQueueConfig.QUEUE)
    public void onTicketRequested(TicketRequested message) {
        boolean processed = workflow.process(message);
        if (!processed) {
            log.info("Ignored duplicate TicketRequested {}", message.messageId());
        }
    }
}
