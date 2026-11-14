package dev.surge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CONFIG_REPO=file:../config-repo")
class ConfigServerTest {

    @LocalServerPort
    int port;

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void servesBookingSettingsFromConfigRepo() throws Exception {
        assertThat(get("/booking-service/default")).contains("\"surge.hold.ttl\":\"PT5M\"");
    }

    @Test
    void servesSharedSettingsToEveryApplication() throws Exception {
        assertThat(get("/anything/default")).contains("management.endpoints.web.exposure.include");
    }
}
