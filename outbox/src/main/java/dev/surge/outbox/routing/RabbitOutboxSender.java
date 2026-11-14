package dev.surge.outbox.routing;

import dev.surge.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Sends the JSON payload as a raw message (content type {@code application/json}, header {@code type}) and blocks
 * until the broker confirms it. Requires {@code spring.rabbitmq.publisher-confirm-type=correlated}.
 */
public class RabbitOutboxSender implements OutboxSender {

    private static final long CONFIRM_TIMEOUT_MILLIS = 5000;

    private final RabbitTemplate template;

    public RabbitOutboxSender(RabbitTemplate template) {
        this.template = template;
    }

    @Override
    public void send(OutboxMessage message, OutboxDestination destination) {
        var rabbit = (OutboxDestination.Rabbit) destination;
        Message amqp = MessageBuilder.withBody(message.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader("type", message.getType())
                .build();
        template.invoke(ops -> {
            ops.send(rabbit.exchange(), rabbit.routingKey(), amqp);
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
            return null;
        });
    }
}
