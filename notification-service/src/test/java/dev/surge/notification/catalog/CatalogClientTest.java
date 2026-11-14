package dev.surge.notification.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.notification.config.ClientsConfig;
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

class CatalogClientTest {

    MockWebServer server = new MockWebServer();
    CatalogClient client;

    @BeforeEach
    void start() throws Exception {
        server.start();
        client = new CatalogClient(ClientsConfig.restClient(RestClient.builder(),
                server.url("/").toString(), Duration.ofMillis(300)));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void returnsTheEventDetails() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).headers(Headers.of("Content-Type", "application/json"))
                .body("""
                        {"id":3,"title":"Coldplay","venueName":"JLN Stadium","city":"Delhi","zoneId":"Asia/Kolkata",
                         "startsAt":"2027-01-18T14:00:00Z","seatsLeft":10,"unknownField":true}""").build());

        CatalogEvent event = client.event(3);

        assertThat(event.title()).isEqualTo("Coldplay");
        assertThat(event.venueName()).isEqualTo("JLN Stadium");
        assertThat(event.zoneId()).isEqualTo("Asia/Kolkata");
        assertThat(event.startsAtLocal().toLocalTime().toString()).isEqualTo("19:30");
        assertThat(server.takeRequest().getTarget()).isEqualTo("/api/catalog/events/3");
    }

    @Test
    void notFoundIsAClearException() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> client.event(77)).isInstanceOf(CatalogEventNotFoundException.class)
                .hasMessageContaining("77");
    }

    @Test
    void slowCatalogTimesOut() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build());

        assertThatThrownBy(() -> client.event(3)).isInstanceOf(ResourceAccessException.class);
    }
}
