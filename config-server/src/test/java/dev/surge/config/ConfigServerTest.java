package dev.surge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    /** Flattens every property source of the served environment (first source wins, as in a client). */
    private Map<String, String> served(String application) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + application + "/default")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
