package dev.surge.notification.audit;

import dev.surge.common.messaging.MessageTypes;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Two retries 1 s apart, then the record goes to orders.v1.audit.DLT (not orders.v1.DLT, nor catalog's DLT).
 * Unparseable or unusable messages cannot succeed on retry and go straight there. The error handler lives on a
 * dedicated container factory (and is deliberately not a bean), so it never applies to any other listener.
 */
@Configuration(proxyBeanMethods = false)
public class AuditKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditKafkaConfig.class);

    static final String CONTAINER_FACTORY = "auditKafkaListenerContainerFactory";
    static final String ORDERS_AUDIT_DLT = MessageTypes.ORDERS_TOPIC + ".audit.DLT";

    @Bean
    NewTopic ordersAuditDeadLetterTopic() {
        return new NewTopic(ORDERS_AUDIT_DLT, 1, (short) 1);
    }

    /** One ERROR line per dead-lettered record: where it was, which message, why. Never the payload. */
    static void logDeadLetter(ConsumerRecord<?, ?> record, Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.error("Audit record dead-lettered to {}: {}-{}@{} messageId={} type={} cause={}: {}", ORDERS_AUDIT_DLT,
                record.topic(), record.partition(), record.offset(), header(record, "messageId"),
                header(record, "type"), cause.getClass().getName(), cause.getMessage());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> auditKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumers,
            KafkaTemplate<Object, Object> template) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumers);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(ORDERS_AUDIT_DLT, 0));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            logDeadLetter(record, ex);
            recoverer.accept(record, ex);
        }, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
