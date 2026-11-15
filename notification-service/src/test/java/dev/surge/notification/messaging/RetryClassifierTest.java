package dev.surge.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;

/** The retry classifier decides between "retry with backoff" and "dead-letter now". No broker needed. */
class RetryClassifierTest {

    private static ListenerExecutionFailedException wrap(Throwable cause) {
        return new ListenerExecutionFailedException("Listener method failed", cause);
    }

    @Test
    void springMessagingConversionErrorsAreNotRetried() {
        var failure = new org.springframework.messaging.converter.MessageConversionException("cannot convert");

        assertThat(RetryConfig.isRetryable(wrap(failure))).isFalse();
    }

    @Test
    void methodArgumentResolutionErrorsAreNotRetried() throws Exception {
        MethodParameter parameter = new MethodParameter(
                TicketRequestedListener.class.getMethod("onTicketRequested",
                        dev.surge.common.messaging.TicketRequested.class), 0);
        var failure = new MethodArgumentResolutionException(
                new org.springframework.messaging.support.GenericMessage<>("x"), parameter, "cannot resolve");

        assertThat(RetryConfig.isRetryable(wrap(failure))).isFalse();
    }

    @Test
    void validationAndAmqpConversionErrorsAreNotRetriedButInfrastructureErrorsAre() {
        assertThat(RetryConfig.isRetryable(wrap(new IllegalArgumentException("bad")))).isFalse();
        assertThat(RetryConfig.isRetryable(wrap(
                new org.springframework.amqp.support.converter.MessageConversionException("bad json")))).isFalse();
        assertThat(RetryConfig.isRetryable(wrap(new IllegalStateException("s3 down")))).isTrue();
        assertThat(RetryConfig.isRetryable(wrap(new RuntimeException(new IOException("smtp"))))).isTrue();
    }
}
