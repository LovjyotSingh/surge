package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.gateway.support.GatewayTestBase;
import io.github.resilience4j.bulkhead.BulkheadConfig;
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
 * The circuit breakers' bulkheads in the running gateway ({@code config.BulkheadConfig}): each has the limit
 * application.yml gives it, none waits for a permit, and none turns away a burst it has room for. How a full bulkhead
 * refuses, and that its refusals never open the breaker, is {@code config.BulkheadConfigTest}.
 *
 * <p>With the library default (25 concurrent calls) the first live 10,000-buyer run let 29 holds through to booking
 * and answered the rest with the "booking is unavailable" fallback.
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
    void bookingAndCatalogHaveTheirOwnLimitsAndNeverWait() {
        // The provider creates a breaker's bulkhead on the first call through it.
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-bulk-size")))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"eventId\":1,\"seatIds\":[1]}")
                .exchange().expectStatus().isOk();
        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();

        // booking: 20,000 Tomcat connections minus up to 15,000 seat streams (application.yml).
        BulkheadConfig booking = bulkheads.getBulkheadRegistry().bulkhead("booking").getBulkheadConfig();
        assertThat(booking.getMaxConcurrentCalls()).isEqualTo(5000);
        assertThat(booking.getMaxWaitDuration()).isZero();

        BulkheadConfig catalog = bulkheads.getBulkheadRegistry().bulkhead("catalog").getBulkheadConfig();
        assertThat(catalog.getMaxConcurrentCalls()).isEqualTo(300);
        assertThat(catalog.getMaxWaitDuration()).isZero();
    }
}
