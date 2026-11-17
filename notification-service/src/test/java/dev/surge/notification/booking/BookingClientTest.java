package dev.surge.notification.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.SurgeEnv;
import dev.surge.notification.config.ClientsConfig;
import dev.surge.notification.support.SignedRequests;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class BookingClientTest {

    MockWebServer server = new MockWebServer();
    BookingClient client;

    @BeforeEach
    void start() throws Exception {
        server.start();
        client = new BookingClient(ClientsConfig.restClient(RestClient.builder(),
                server.url("/").toString(), Duration.ofMillis(300), IdentityTokens.signer("svc:notification"),
                "booking-service", SurgeEnv.DEV));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void readsTheOwnersOrderFromTheInternalEndpointAsNotification() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).headers(Headers.of("Content-Type", "application/json"))
                .body("""
                        {"orderId":42,"eventId":3,"status":"CONFIRMED","totalInr":4499.00,"createdAt":"2027-01-01T10:00:00Z",
                         "lines":[{"seatId":11,"rowLabel":"A","number":1,"priceInr":2000.00},
                                  {"seatId":12,"rowLabel":"A","number":2,"priceInr":2499.00}],"extra":true}""").build());

        BookingOrder order = client.order("u-7", 42);

        assertThat(order.orderId()).isEqualTo(42);
        assertThat(order.eventId()).isEqualTo(3);
        assertThat(order.totalInr()).isEqualByComparingTo(new BigDecimal("4499"));
        assertThat(order.lines()).hasSize(2);
        assertThat(order.lines().get(1).rowLabel()).isEqualTo("A");
        assertThat(order.lines().get(1).number()).isEqualTo(2);
        assertThat(order.seatLabels()).containsExactly("A1", "A2");
        var request = server.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/internal/orders/42?userId=u-7");
        // signed as notification for booking, and never as the user
        SignedRequests.assertSignedFor(request, "booking-service");
    }

    @Test
    void notFoundIsAClearException() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> client.order("u-7", 77)).isInstanceOf(BookingOrderNotFoundException.class)
                .hasMessageContaining("77");
    }

    @Test
    void invalidUserIdNeverReachesTheNetwork() {
        for (String bad : new String[] {null, "", "a b", "../x", "x".repeat(65), "u-1\r\nX-Evil: 1"}) {
            assertThatThrownBy(() -> client.order(bad, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void serverErrorPropagatesSoTheMessageIsRetried() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        assertThatThrownBy(() -> client.order("u-7", 1)).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void slowBookingTimesOut() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build());

        assertThatThrownBy(() -> client.order("u-7", 1)).isInstanceOf(ResourceAccessException.class);
    }
}
