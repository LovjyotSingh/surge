package dev.surge.catalog.config;

import dev.surge.common.messaging.MessageTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * Five attempts in all: a failing record is retried after 1, 2, 4 and 8 s, then goes to orders.v1.catalog.DLT (not
 * orders.v1.DLT, which is another consumer's). Catalog decrements seatsLeft once per OrderConfirmed, so a dead-lettered
 * message is a permanent drift: a transient failure (the database restarting, a deadlock, a lost connection) gets
 * about 15 s to clear before that happens. Unparseable or invalid payloads cannot succeed on retry and go straight
 * to the dead-letter topic.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    static final String ORDERS_DLT = MessageTypes.ORDERS_TOPIC + ".catalog.DLT";
    /** Retries after the first attempt: 1, 2, 4 and 8 s. */
    static final int RETRIES = 4;

    @Bean
    NewTopic ordersDeadLetterTopic() {
        return new NewTopic(ORDERS_DLT, 1, (short) 1);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(ORDERS_DLT, 0));
        return errorHandler(recoverer);
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
        return poisonGoesStraightToTheRecoverer(new DefaultErrorHandler(recoverer, retryBackOff()));
    }

    /** As {@link #errorHandler(ConsumerRecordRecoverer)}, with the wait between attempts in the caller's hands (tests). */
    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, BackOffHandler backOffHandler) {
        return poisonGoesStraightToTheRecoverer(new DefaultErrorHandler(recoverer, retryBackOff(), backOffHandler));
    }

    private static DefaultErrorHandler poisonGoesStraightToTheRecoverer(DefaultErrorHandler handler) {
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }

    /** 1 s, doubling, at most 8 s between attempts, {@value #RETRIES} retries. */
    static BackOff retryBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(8000L);
        backOff.setMaxAttempts(RETRIES);
        return backOff;
    }
}
