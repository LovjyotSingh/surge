package dev.surge.notification.messaging;

import dev.surge.common.messaging.MessageTypes;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker resources of the ticket worker. Booking publishes with {@code mandatory=true}, so an unroutable publish
 * fails and its outbox row waits: this service MUST declare the queue and its binding, or booking stalls.
 * Rejected messages are dead-lettered by the broker through {@code surge.tickets.dlx} (fanout) into
 * {@code tickets.dlq}; no dead-letter routing key is set, so the original key is kept.
 */
@Configuration(proxyBeanMethods = false)
public class TicketQueueConfig {

    public static final String QUEUE = "tickets";
    public static final String DLX = "surge.tickets.dlx";
    public static final String DLQ = "tickets.dlq";

    /** Same arguments as booking's own declaration (durable, no auto-delete), so declaring it twice is harmless. */
    @Bean
    DirectExchange ticketsExchange() {
        return new DirectExchange(MessageTypes.TICKETS_EXCHANGE, true, false);
    }

    @Bean
    Queue ticketsQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).build();
    }

    @Bean
    Binding ticketsBinding(Queue ticketsQueue, DirectExchange ticketsExchange) {
        return BindingBuilder.bind(ticketsQueue).to(ticketsExchange).with(MessageTypes.TICKET_REQUESTED_KEY);
    }

    @Bean
    FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        // Classic on purpose: a quorum default would drop listed (nack+requeue) messages at its delivery limit.
        return QueueBuilder.durable(DLQ).withArgument("x-queue-type", "classic").build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    /** Booking's payload is raw JSON with no Java type header; the listener method's parameter type decides. */
    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
