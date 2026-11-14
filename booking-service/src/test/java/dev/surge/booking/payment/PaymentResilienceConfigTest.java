package dev.surge.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.support.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** The Global Constraints numbers for the {@code payment} circuit breaker and retry, as read from application.yml. */
@IntegrationTest
class PaymentResilienceConfigTest {

    @Autowired CircuitBreakerRegistry breakers;
    @Autowired RetryRegistry retries;

    @Test
    void circuitBreakerPaymentMatchesTheGlobalConstraints() {
        CircuitBreakerConfig c = breakers.circuitBreaker("payment").getCircuitBreakerConfig();

        assertThat(c.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(c.getSlidingWindowSize()).isEqualTo(20);
        assertThat(c.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(c.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(2));
        assertThat(c.getSlowCallRateThreshold()).isEqualTo(100f);
        assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
        assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        // I/O errors, 5xx and read timeouts count; a decline (a normal 200) and 4xx do not.
        assertThat(c.getRecordExceptionPredicate().test(new ResourceAccessException("io"))).isTrue();
        assertThat(c.getRecordExceptionPredicate().test(new PaymentTimeoutException("slow", null))).isTrue();
        assertThat(c.getRecordExceptionPredicate().test(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null))).isTrue();
        assertThat(c.getRecordExceptionPredicate().test(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x", null, null, null))).isFalse();
    }

    @Test
    void retryPaymentMatchesTheGlobalConstraints() {
        RetryConfig r = retries.retry("payment").getRetryConfig();

        assertThat(r.getMaxAttempts()).isEqualTo(2);
        // retried: I/O and 5xx. Never: a 4xx, an open circuit, a read timeout.
        assertThat(r.getExceptionPredicate().test(new ResourceAccessException("io"))).isTrue();
        assertThat(r.getExceptionPredicate().test(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", null, null, null))).isTrue();
        assertThat(r.getExceptionPredicate().test(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "x", null, null, null))).isFalse();
        assertThat(r.getExceptionPredicate().test(new PaymentTimeoutException("slow", null))).isFalse();
    }
}
