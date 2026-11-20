package dev.surge.notification.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.SurgeEnv;
import dev.surge.notification.config.ClientsConfig;
import dev.surge.notification.support.SignedRequests;
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

class UserDirectoryTest {

    MockWebServer server = new MockWebServer();
    UserDirectory directory;

    @BeforeEach
    void start() throws Exception {
        server.start();
        directory = new UserDirectory(ClientsConfig.restClient(RestClient.builder(),
                server.url("/").toString(), Duration.ofMillis(300), IdentityTokens.signer("svc:notification"),
                "auth-service", SurgeEnv.DEV));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void returnsTheUsersEmail() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).headers(Headers.of("Content-Type", "application/json"))
                .body("""
                        {"id":"u-7","email":"asha@example.com","displayName":"Asha","roles":["USER"]}""").build());

        assertThat(directory.email("u-7")).isEqualTo("asha@example.com");
        var request = server.takeRequest();
        assertThat(request.getTarget()).isEqualTo("/internal/users/u-7");
        SignedRequests.assertSignedFor(request, "auth-service");
    }

    @Test
    void unknownUserThrows() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        assertThatThrownBy(() -> directory.email("u-404")).isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("u-404");
    }

    @Test
    void slowAuthTimesOut() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build());

        assertThatThrownBy(() -> directory.email("u-7")).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void invalidIdsAreRejectedWithoutAnHttpCall() {
        for (String bad : new String[] {"..", ".", null, "", "a".repeat(65), "a/b", "lt-../x"}) {
            assertThatThrownBy(() -> directory.email(bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid user id");
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void loadTestUsersMakeNoHttpCall() {
        assertThat(directory.email("lt-run1-5")).isEqualTo("lt-run1-5@loadtest.local");
        assertThat(server.getRequestCount()).isZero();
    }
}
