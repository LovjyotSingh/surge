package dev.surge.catalog.config;

import dev.surge.common.messaging.MessageTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Two retries 1 s apart, then the record goes to orders.v1.catalog.DLT (not orders.v1.DLT, which is another
 * consumer's). Unparseable or invalid payloads cannot succeed on retry and go straight there.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    static final String ORDERS_DLT = MessageTypes.ORDERS_TOPIC + ".catalog.DLT";

    @Bean
    NewTopic ordersDeadLetterTopic() {
        return new NewTopic(ORDERS_DLT, 1, (short) 1);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(ORDERS_DLT, 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
