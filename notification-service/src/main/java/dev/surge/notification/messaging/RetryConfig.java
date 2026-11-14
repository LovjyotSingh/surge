package dev.surge.notification.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryException;

/**
 * Listener retry. The attempts, delays and multiplier come from {@code spring.rabbitmq.listener.simple.retry.*};
 * this class only adds the classification: a message that can never succeed is not retried.
 * Non-retryable: malformed JSON or a wrong shape (conversion), and anything the validation rejects
 * ({@link IllegalArgumentException}, for example an invalid user id). Retryable: everything else (S3, SMTP, a
 * peer service down, a 404 from booking that may only be replication lag, database errors).
 * When the attempts run out (or the exception is not retryable) the recoverer rejects the message without requeue
 * and the broker dead-letters it to {@code tickets.dlq}.
 */
@Configuration(proxyBeanMethods = false)
public class RetryConfig {

    private static final Logger log = LoggerFactory.getLogger(RetryConfig.class);

    @Bean
    RabbitListenerRetrySettingsCustomizer ticketRetryClassifier() {
        return settings -> settings.setExceptionPredicate(RetryConfig::isRetryable);
    }

    static boolean isRetryable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IllegalArgumentException || t instanceof MessageConversionException
                    || t instanceof org.springframework.messaging.converter.MessageConversionException
                    || t instanceof org.springframework.messaging.handler.invocation.MethodArgumentResolutionException) {
                return false;
            }
        }
        return true;
    }

    /** Same as the default recoverer (reject, no requeue), but the reason is written to the log before it is lost. */
    @Bean
    MessageRecoverer ticketRecoverer() {
        MessageRecoverer reject = new RejectAndDontRequeueRecoverer();
        return (Message message, Throwable cause) -> {
            Throwable reason = cause instanceof RetryException && cause.getCause() != null ? cause.getCause() : cause;
            log.error("Dead-lettering a ticket message to tickets.dlq: {}", rootMessage(reason), reason);
            reject.recover(message, cause);
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
