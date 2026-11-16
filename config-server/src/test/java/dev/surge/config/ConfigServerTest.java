package dev.surge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "CONFIG_REPO=file:../config-repo")
class ConfigServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    /** What every service's application.yml sends when CONFIG_USER / CONFIG_PASSWORD are unset. */
    private static final String DEV_CREDENTIALS = "Basic " + Base64.getEncoder()
            .encodeToString("surge-dev:dev-only-config-password".getBytes(StandardCharsets.UTF_8));

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** SEC-15: any container could otherwise read every service's configuration. */
    @Test
    void withoutCredentialsOnlyHealthAnswers() throws Exception {
        assertThat(get("/booking-service/default", null).statusCode()).isEqualTo(401);
        assertThat(get("/application/default", null).statusCode()).isEqualTo(401);
        assertThat(get("/booking-service/default", "Basic " + Base64.getEncoder()
                .encodeToString("surge-dev:wrong".getBytes(StandardCharsets.UTF_8))).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    /** Flattens every property source of the served environment (first source wins, as in a client). */
    private Map<String, String> served(String application) throws Exception {
        HttpResponse<String> response = get("/" + application + "/default", DEV_CREDENTIALS);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode sources = JSON.readTree(response.body()).get("propertySources");
        Map<String, String> flat = new HashMap<>();
        List<JsonNode> list = sources.valueStream().toList();
        for (int i = list.size() - 1; i >= 0; i--) {
            list.get(i).get("source").properties().forEach(e -> flat.put(e.getKey(), e.getValue().asString()));
        }
        return flat;
    }

    @Test
    void servesBookingSettingsFromConfigRepo() throws Exception {
        assertThat(served("booking-service")).containsEntry("surge.hold.ttl", "PT5M");
    }

    @Test
    void servesSharedSettingsToEveryApplication() throws Exception {
        assertThat(served("anything")).containsEntry("management.endpoints.web.exposure.include", "health,info,prometheus");
    }

    @Test
    void servesNoPlaceholdersAndNoEurekaZone() throws Exception {
        Map<String, String> shared = served("anything");
        System.out.println("OBSERVED defaultZone = " + shared.get("eureka.client.service-url.defaultZone"));
        assertThat(shared.keySet()).noneMatch(k -> k.contains("defaultZone"));
        assertThat(shared.values()).noneMatch(v -> v.contains("${"));
    }
}
