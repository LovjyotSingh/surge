package dev.surge.booking.config;

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
 * Booking owns the error handling for the topics it consumes: 2 retries, 1 s apart, then the record goes to
 * {@code <topic>.DLT} (partition 0). Unparseable or invalid payloads cannot succeed on retry and go straight there.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    static final String CATALOG_DLT = MessageTypes.CATALOG_TOPIC + ".DLT";

    @Bean
    NewTopic catalogDeadLetterTopic() {
        return new NewTopic(CATALOG_DLT, 1, (short) 1);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
