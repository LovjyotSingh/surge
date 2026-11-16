package dev.surge.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalTokenSigner;
import dev.surge.notification.booking.BookingClient;
import dev.surge.notification.catalog.CatalogClient;
import dev.surge.notification.support.SignedRequests;
import dev.surge.notification.user.UserDirectory;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The real client beans of {@link ClientsConfig}: each one signs as {@code svc:notification} for its own target and
 * no other, whatever URL it is pointed at; a base URL that names a different service stops the application.
 */
class ClientsWiringTest {

    private final MockWebServer server = new MockWebServer();
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server.start();
        String stub = server.url("/").toString();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .withUserConfiguration(ClientsConfig.class)
                .withBean(InternalTokenSigner.class, () -> IdentityTokens.signer("svc:notification"))
                .withPropertyValues("surge.clients.booking-base-url=" + stub, "surge.clients.auth-base-url=" + stub,
                        "surge.clients.catalog-base-url=" + stub);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void json(String body) {
        server.enqueue(new MockResponse.Builder().code(200).headers(Headers.of("Content-Type", "application/json"))
                .body(body).build());
    }

    @Test
    void everyClientSignsForItsOwnTarget() {
        runner.run(context -> {
            json("""
                    {"orderId":42,"eventId":3,"totalInr":10.00,
                     "lines":[{"seatId":1,"rowLabel":"A","number":1,"priceInr":10.00}]}""");
            context.getBean(BookingClient.class).order("u-7", 42);
            SignedRequests.assertSignedFor(server.takeRequest(), "booking-service");

            json("""
                    {"id":"u-7","email":"asha@example.com","displayName":"Asha","roles":["USER"]}""");
            context.getBean(UserDirectory.class).email("u-7");
            SignedRequests.assertSignedFor(server.takeRequest(), "auth-service");

            json("""
                    {"id":3,"title":"T","venueName":"V","city":"C","zoneId":"Asia/Kolkata",
                     "startsAt":"2027-01-18T14:00:00Z"}""");
            context.getBean(CatalogClient.class).event(3);
            SignedRequests.assertSignedFor(server.takeRequest(), "catalog-service");
        });
    }

    @Test
    void aBaseUrlThatNamesAnotherServiceFailsStartup() {
        runner.withPropertyValues("surge.clients.catalog-base-url=http://booking-service").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("wrong service"));
        runner.withPropertyValues("surge.clients.auth-base-url=http://notification-service:8086").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("wrong service"));
        // the service names they should have start fine (resolved through Eureka in the app)
        runner.withPropertyValues("surge.clients.catalog-base-url=http://catalog-service",
                "surge.clients.auth-base-url=http://auth-service",
                "surge.clients.booking-base-url=http://booking-service")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
