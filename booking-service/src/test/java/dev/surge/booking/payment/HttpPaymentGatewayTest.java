package dev.surge.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The circuit breaker, retry and timeout mechanics of {@link HttpPaymentGateway}, against a MockWebServer standing in
 * for payment-service. No Spring context: the registries are built here from the same numbers as booking's
 * application.yml ({@code PaymentResilienceConfigTest} proves the yml says these numbers), except that the open-state
 * wait is shortened so the recovery test is fast.
 */
class HttpPaymentGatewayTest {

    private static final String APPROVED = "{\"approved\":true,\"paymentId\":\"pay_1\",\"declineReason\":null}";
    private static final String DECLINED = "{\"approved\":false,\"paymentId\":\"pay_2\",\"declineReason\":\"card_declined\"}";
    private static final PaymentRequest REQUEST = new PaymentRequest("u1:k1", "u1", new BigDecimal("4999.00"));

    private MockWebServer server;
    private CircuitBreaker breaker;
    private HttpPaymentGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .slowCallRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(500)) // 10 s in the app
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class,
                        PaymentTimeoutException.class)
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(50))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        breaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("payment");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient client = RestClient.builder().baseUrl(server.url("/").toString()).requestFactory(factory).build();
        gateway = new HttpPaymentGateway(client, breaker, RetryRegistry.of(retryConfig).retry("payment"));
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }

    private static Dispatcher always(int code, String body) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return json(code, body);
            }
        };
    }

    private void enqueue(int times, int code, String body) {
        for (int i = 0; i < times; i++) {
            server.enqueue(json(code, body));
        }
    }

    @Test
    void approvedChargeReturnsThePaymentId() throws Exception {
        enqueue(1, 200, APPROVED);

        PaymentResult result = gateway.charge(REQUEST);

        assertThat(result.approved()).isTrue();
        assertThat(result.paymentId()).isEqualTo("pay_1");
        var recorded = server.takeRequest();
        assertThat(recorded.getTarget()).isEqualTo("/api/payments");
        assertThat(recorded.getBody().utf8()).contains("\"reference\":\"u1:k1\"").contains("\"userId\":\"u1\"")
                .contains("4999");
    }

    @Test
    void declineIsNotAFailure() {
        enqueue(10, 200, DECLINED);

        for (int i = 0; i < 10; i++) {
            PaymentResult result = gateway.charge(REQUEST);
            assertThat(result.approved()).isFalse();
            assertThat(result.declineReason()).isEqualTo("card_declined");
        }

        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(server.getRequestCount()).isEqualTo(10);
    }

    @Test
    void retryOnlyOn5xx() {
        enqueue(1, 500, "{}");
        enqueue(1, 200, APPROVED);
        assertThat(gateway.charge(REQUEST).approved()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);

        enqueue(1, 200, DECLINED);
        assertThat(gateway.charge(REQUEST).approved()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(3); // a decline is never retried

        enqueue(1, 400, "{}");
        assertThatThrownBy(() -> gateway.charge(REQUEST)).isNotInstanceOf(PaymentUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(4); // a 4xx is never retried
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1); // only the 500
    }

    @Test
    void exhaustedRetriesBecomePaymentUnavailable() {
        enqueue(2, 500, "{}");

        assertThatThrownBy(() -> gateway.charge(REQUEST)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void slowPaymentTimesOutWithin2s() {
        server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body(APPROVED).headersDelay(5, TimeUnit.SECONDS).build());

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.charge(REQUEST)).isInstanceOf(PaymentUnavailableException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(2500); // a read timeout is not retried: the user is waiting
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void connectionRefusedIsRetriedThenUnavailable() throws Exception {
        server.close(); // nothing listens on the port any more

        assertThatThrownBy(() -> gateway.charge(REQUEST)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2); // both attempts counted
    }

    @Test
    void openCircuitFailsFastWithoutCallingTheService() {
        driveOpen();
        int requestsWhenOpened = server.getRequestCount();

        assertThatThrownBy(() -> gateway.charge(REQUEST)).isInstanceOf(PaymentUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThatThrownBy(() -> gateway.refund("pay_1")).isInstanceOf(PaymentUnavailableException.class);
        assertThat(server.getRequestCount()).isEqualTo(requestsWhenOpened);
    }

    @Test
    void circuitClosesAfterRecovery() throws Exception {
        driveOpen();
        Thread.sleep(600); // open-state wait is 500 ms in this test

        server.setDispatcher(always(200, APPROVED));
        for (int i = 0; i < 3; i++) {
            assertThat(gateway.charge(REQUEST).approved()).isTrue();
        }

        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void refundPostsToThePaymentAndFailuresAreThrown() throws Exception {
        enqueue(1, 200, "{\"refunded\":true}");
        gateway.refund("pay_1");
        assertThat(server.takeRequest().getTarget()).isEqualTo("/api/payments/pay_1/refund");

        enqueue(2, 500, "{}");
        assertThatThrownBy(() -> gateway.refund("pay_1")).isInstanceOf(PaymentUnavailableException.class);
    }

    /** Sends failing charges until the breaker opens (5 recorded failures with a minimum of 5 calls). */
    private void driveOpen() {
        server.setDispatcher(always(500, "{}"));
        for (int i = 0; i < 3 && breaker.getState() != State.OPEN; i++) {
            assertThatThrownBy(() -> gateway.charge(REQUEST)).isInstanceOf(PaymentUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }
}
