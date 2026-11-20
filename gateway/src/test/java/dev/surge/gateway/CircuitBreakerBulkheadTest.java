package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.gateway.config.BulkheadConfig;
import dev.surge.gateway.support.GatewayTestBase;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * The circuit breakers' bulkhead never turns concurrent buyers away by itself ({@link BulkheadConfig}).
 *
 * <p>Spring Cloud CircuitBreaker wraps each circuit breaker in a Resilience4j semaphore bulkhead. With the library
 * default (25 concurrent calls, no waiting) the first live 10,000-buyer run let 29 holds through to booking and
 * answered the rest with the "booking is unavailable" fallback.
 */
class CircuitBreakerBulkheadTest extends GatewayTestBase {

    private static final int CONCURRENT_HOLDS = 60;

    @Autowired
    ReactiveResilience4jBulkheadProvider bulkheads;

    @Test
    void manyMoreThanTwentyFiveConcurrentHoldsAllReachBooking() {
        BOOKING.respondWith(request -> new MockResponse.Builder().code(201)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"holdId\":\"h\"}")
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .build());
        WebClient web = WebClient.create("http://localhost:" + port);

        // Each hold from its own client address and user, so neither the per-IP nor the per-user hold bucket refuses.
        List<Integer> statuses = Flux.range(0, CONCURRENT_HOLDS)
                .flatMap(i -> web.post().uri("/api/bookings/holds")
                        .header("X-Forwarded-For", freshClientIp())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-bulk-" + i)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"eventId\":1,\"seatIds\":[1]}")
                        .exchangeToMono(response -> response.releaseBody()
                                .thenReturn(response.statusCode().value())), CONCURRENT_HOLDS)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(statuses).hasSize(CONCURRENT_HOLDS).containsOnly(201);
    }

    @Test
    void theBulkheadIsSizedToBookingsCapacityAndNeverWaits() {
        // The provider creates the "booking" bulkhead, with its configured default, on the first call through it.
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-bulk-size")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"eventId\":1,\"seatIds\":[1]}")
                .exchange().expectStatus().isOk();

        var config = bulkheads.getBulkheadRegistry().bulkhead("booking").getBulkheadConfig();
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(BulkheadConfig.MAX_CONCURRENT_CALLS);
        // Waiting for a permit would block a Netty event loop thread.
        assertThat(config.getMaxWaitDuration()).isZero();
    }
}
