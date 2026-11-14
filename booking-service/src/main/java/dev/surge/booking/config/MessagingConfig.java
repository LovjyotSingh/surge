package dev.surge.booking.config;

import dev.surge.common.messaging.MessageTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker resources owned by booking: the {@code orders.v1} topic and the {@code surge.tickets} exchange. The tickets
 * queue and its dead-letter setup belong to the consumer (notification), not to booking.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    @Bean
    NewTopic ordersTopic() {
        return new NewTopic(MessageTypes.ORDERS_TOPIC, 3, (short) 1);
    }

    @Bean
    DirectExchange ticketsExchange() {
        return new DirectExchange(MessageTypes.TICKETS_EXCHANGE, true, false);
    }
}
