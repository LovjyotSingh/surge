package dev.surge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.smoke.StubServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RouteWaitTest {

    private static final String UNAVAILABLE =
        "{\"type\":\"https://surge.dev/problems/service-unavailable\",\"status\":503}";
    private static final String BOOKING = "GET /api/bookings/events/7";
    private static final String CATALOG = "GET /api/catalog/events/search";

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

    @Test
    void waitsUntilTheGatewayRoutesAgain() throws Exception {
        try (StubServer stub = new StubServer()
            .on(BOOKING, Reply.of(503, UNAVAILABLE), Reply.of(503, UNAVAILABLE), Reply.of(200, "{}"))
            .on(CATALOG, Reply.of(200, "{}")); Api api = new Api(stub.baseUrl())) {

            RouteWait.await(api, 7, Duration.ofSeconds(5), Duration.ofMillis(5), out);

            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Waiting for the gateway to route to booking "
                + "and catalog").contains("ready after ").doesNotContain("gave up");
            assertThat(stub.received.stream().filter(BOOKING::equals).count()).isEqualTo(3);
        }
    }

    @Test
    void doesNotWaitWhenEverythingAnswersAtOnce() throws Exception {
        try (StubServer stub = new StubServer().on(BOOKING, Reply.of(200, "{}")).on(CATALOG, Reply.of(200, "{}"));
             Api api = new Api(stub.baseUrl())) {

            RouteWait.await(api, 7, Duration.ofSeconds(5), Duration.ofMillis(5), out);

            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("ready after 0 s");
            assertThat(stub.received).containsExactly(BOOKING, CATALOG);
        }
    }

    @Test
    void givesUpWithoutThrowingWhenTheRouteNeverComes() throws Exception {
        try (StubServer stub = new StubServer().on(BOOKING, Reply.of(503, UNAVAILABLE))
            .on(CATALOG, Reply.of(200, "{}")); Api api = new Api(stub.baseUrl())) {

            RouteWait.await(api, 7, Duration.ofMillis(50), Duration.ofMillis(5), out);

            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("gave up after 0 s")
                .contains("HTTP 503 service-unavailable");
        }
    }
}
