package dev.surge.outbox;

import dev.surge.outbox.routing.KafkaOutboxSender;
import dev.surge.outbox.routing.OutboxSender;
import dev.surge.outbox.routing.RabbitOutboxSender;
import dev.surge.outbox.routing.RoutingOutboxPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Registers the {@link RoutingOutboxPublisher} when a {@code KafkaTemplate} and/or {@code RabbitTemplate} exists.
 * Runs after Boot's Kafka and Rabbit auto-configurations (so their templates are visible to the conditions) and
 * before {@link OutboxAutoConfiguration}, so the logging fallback there (conditional on no OutboxPublisher) sees
 * this publisher and steps aside. The Kafka and Rabbit dependencies are optional: each sender lives in a nested
 * configuration guarded by {@code @ConditionalOnClass}, so a service without that broker never loads its classes.
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"},
        before = OutboxAutoConfiguration.class)
public class OutboxRoutingAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    static class KafkaSenderConfiguration {

        @Bean
        @ConditionalOnBean(KafkaTemplate.class)
        @SuppressWarnings("unchecked")
        OutboxSender kafkaOutboxSender(KafkaTemplate<?, ?> template) {
            return new KafkaOutboxSender((KafkaTemplate<String, String>) template);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    static class RabbitSenderConfiguration {

        @Bean
        @ConditionalOnBean(RabbitTemplate.class)
        OutboxSender rabbitOutboxSender(RabbitTemplate template) {
            return new RabbitOutboxSender(template);
        }
    }

    @Bean
    @ConditionalOnMissingBean(OutboxPublisher.class)
    @ConditionalOnBean(OutboxSender.class)
    RoutingOutboxPublisher routingOutboxPublisher(
            @Qualifier("kafkaOutboxSender") ObjectProvider<OutboxSender> kafka,
            @Qualifier("rabbitOutboxSender") ObjectProvider<OutboxSender> rabbit) {
        return new RoutingOutboxPublisher(kafka.getIfAvailable(), rabbit.getIfAvailable());
    }
}
