package dev.surge.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * SEC-04: Eureka answers only to HTTP Basic credentials (here the development ones: no EUREKA_USER/EUREKA_PASSWORD
 * in tests). Health stays open for container checks, and Eureka clients need no CSRF token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryApplicationTest {

    /** What every service's application.yml sends when EUREKA_USER / EUREKA_PASSWORD are unset. */
    private static final String DEV_CREDENTIALS = basic("surge-dev", "dev-only-eureka-password");

    private static final String INSTANCE = """
            {"instance":{"instanceId":"test-1","hostName":"localhost","app":"TEST-APP","ipAddr":"127.0.0.1",
             "status":"UP","port":{"$":8080,"@enabled":"true"},
             "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"}}}
            """;

    @LocalServerPort
    int port;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder to(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", "application/json");
    }

    @Test
    void registryEndpointAnswersWithCredentials() throws Exception {
        HttpResponse<String> response = send(to("/eureka/apps").header("Authorization", DEV_CREDENTIALS));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("applications");
    }

    @Test
    void withoutCredentialsEverythingButHealthIs401() throws Exception {
        for (String path : new String[] {"/eureka/apps", "/eureka/apps/BOOKING-SERVICE", "/", "/actuator/info"}) {
            assertThat(send(to(path)).statusCode()).as(path).isEqualTo(401);
        }
        assertThat(send(to("/eureka/apps").header("Authorization", basic("surge-dev", "wrong"))).statusCode())
                .isEqualTo(401);
        assertThat(send(to("/actuator/health")).statusCode()).isEqualTo(200);
    }

    /** The hijack from SEC-04: registering (or removing) an instance needs the credentials. */
    @Test
    void registeringAnInstanceNeedsCredentialsButNoCsrfToken() throws Exception {
        HttpRequest.Builder register = to("/eureka/apps/TEST-APP").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(INSTANCE));
        assertThat(send(register).statusCode()).isEqualTo(401);
        assertThat(send(to("/eureka/apps/TEST-APP/test-1").DELETE()).statusCode()).isEqualTo(401);

        assertThat(send(to("/eureka/apps/TEST-APP").header("Content-Type", "application/json")
                .header("Authorization", DEV_CREDENTIALS).POST(HttpRequest.BodyPublishers.ofString(INSTANCE)))
                .statusCode()).isEqualTo(204);
        assertThat(send(to("/eureka/apps/TEST-APP/test-1").header("Authorization", DEV_CREDENTIALS).DELETE())
                .statusCode()).isEqualTo(200);
    }
}
