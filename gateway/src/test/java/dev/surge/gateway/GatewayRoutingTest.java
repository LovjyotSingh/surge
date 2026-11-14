package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.gateway.support.GatewayTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/** The route table and the cross-cutting behaviour the brief asks for. Extra security cases: {@link GatewaySecurityTest}. */
class GatewayRoutingTest extends GatewayTestBase {

    private static final String PROBLEM = "https://surge.dev/problems/";

    @Test
    void publicCatalogNeedsNoToken() throws Exception {
        client.get().uri("/api/catalog/events").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.ok").isEqualTo(true);

        RecordedRequest seen = CATALOG.next();
        assertThat(seen).isNotNull();
        assertThat(seen.getTarget()).isEqualTo("/api/catalog/events");
        assertThat(seen.getHeaders().get("X-User-Id")).isNull();
    }

    @Test
    void holdsWithoutTokenIs401Problem() throws Exception {
        client.post().uri("/api/bookings/holds").bodyValue("{}").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.requestId").isNotEmpty();

        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void forgedHeadersAreReplaced() throws Exception {
        client.post().uri("/api/bookings/holds")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-1")))
                .header("X-User-Id", "evil")
                .header("X-User-Email", "evil@evil.test")
                .header("X-User-Roles", "ADMIN")
                .bodyValue("{}").exchange().expectStatus().isOk();

        RecordedRequest seen = BOOKING.next();
        assertThat(seen).isNotNull();
        assertThat(seen.getHeaders().values("X-User-Id")).containsExactly("u-1");
        assertThat(seen.getHeaders().values("X-User-Email")).containsExactly("u-1@surge.test");
        assertThat(seen.getHeaders().values("X-User-Roles")).containsExactly("USER");
        assertThat(seen.getHeaders().get("Authorization")).as("the bearer token never reaches a service").isNull();
    }

    @Test
    void forgedHeadersWithoutTokenOnPublicRouteAreDropped() throws Exception {
        client.get().uri("/api/catalog/events")
                .header("X-User-Id", "evil").header("X-User-Email", "evil@evil.test").header("X-User-Roles", "ADMIN")
                .exchange().expectStatus().isOk();

        RecordedRequest seen = CATALOG.next();
        assertThat(seen).isNotNull();
        assertThat(seen.getHeaders().get("X-User-Id")).isNull();
        assertThat(seen.getHeaders().get("X-User-Email")).isNull();
        assertThat(seen.getHeaders().get("X-User-Roles")).isNull();
    }

    @Test
    void adminRouteNeedsAdminRole() throws Exception {
        client.post().uri("/api/bookings/admin/reset")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-2"))).exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "forbidden")
                .jsonPath("$.requestId").isNotEmpty();
        assertThat(BOOKING.sawNothing()).isTrue();

        client.post().uri("/api/bookings/admin/reset").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        assertThat(BOOKING.sawNothing()).isTrue();

        client.post().uri("/api/bookings/admin/reset")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken("u-admin"))).exchange()
                .expectStatus().isOk();
        RecordedRequest seen = BOOKING.next();
        assertThat(seen).isNotNull();
        assertThat(seen.getTarget()).isEqualTo("/api/bookings/admin/reset");
        assertThat(seen.getHeaders().get("X-User-Id")).isEqualTo("u-admin");
        assertThat(seen.getHeaders().get("X-User-Roles")).isEqualTo("USER,ADMIN");
    }

    @Test
    void expiredOrWrongIssuerTokenIs401() throws Exception {
        String expired = token("u-3", "u-3@surge.test", List.of("USER"), ISSUER,
                Instant.now().minus(10, ChronoUnit.MINUTES), KEY);
        String wrongIssuer = token("u-3", "u-3@surge.test", List.of("USER"), "someone-else",
                Instant.now().plus(1, ChronoUnit.HOURS), KEY);
        String urlIssuer = token("u-3", "u-3@surge.test", List.of("USER"), "https://surge-auth/",
                Instant.now().plus(1, ChronoUnit.HOURS), KEY);

        for (String bad : List.of(expired, wrongIssuer, urlIssuer)) {
            client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(bad))
                    .bodyValue("{}").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        }
        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        String token = bearer(userToken("u-rate"));
        List<EntityExchangeResult<byte[]>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue("{}").exchange().expectBody().returnResult());
        }
        // the burst of 10 is served, the rest is refused (a couple of tokens may refill while the loop runs)
        assertThat(results.subList(0, 10)).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(HttpStatus.OK));
        List<EntityExchangeResult<byte[]>> limited = results.stream()
                .filter(r -> r.getStatus() == HttpStatus.TOO_MANY_REQUESTS).toList();
        assertThat(limited).isNotEmpty();
        EntityExchangeResult<byte[]> denied = limited.get(0);
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(denied.getResponseHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(new String(denied.getResponseBodyContent())).contains(PROBLEM + "rate-limited")
                .contains("requestId");

        // another user is unaffected
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-rate-other")))
                .bodyValue("{}").exchange().expectStatus().isOk();
    }

    @Test
    void downstreamDownGives503Problem() throws Exception {
        CATALOG.stop();
        try {
            client.get().uri("/api/catalog/events").header("X-Request-Id", "req-down-1").exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.type").isEqualTo(PROBLEM + "service-unavailable")
                    .jsonPath("$.requestId").isEqualTo("req-down-1");
        } finally {
            CATALOG.restart();
        }
        // and it works again once the service is back
        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();
    }

    @Test
    void requestIdPropagatedAndEchoed() throws Exception {
        client.get().uri("/api/catalog/events").header("X-Request-Id", "abc-123_X.y").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "abc-123_X.y");
        assertThat(CATALOG.next().getHeaders().values("X-Request-Id")).containsExactly("abc-123_X.y");

        // malformed ids are replaced by a generated UUID, also on gateway-made errors
        String bad = "not valid!";
        EntityExchangeResult<byte[]> replaced = client.get().uri("/api/catalog/events").header("X-Request-Id", bad)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        String generated = replaced.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(replaced.getResponseHeaders().get("X-Request-Id")).hasSize(1);
        assertThat(CATALOG.next().getHeaders().values("X-Request-Id")).containsExactly(generated);

        client.post().uri("/api/bookings/holds").exchange().expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody().jsonPath("$.requestId").value(id -> assertThat(UUID.fromString((String) id)).isNotNull());

        String tooLong = "a".repeat(65);
        client.get().uri("/api/catalog/events").header("X-Request-Id", tooLong).exchange()
                .expectHeader().value("X-Request-Id", id -> assertThat(id).isNotEqualTo(tooLong));
    }

    @Test
    void sseRouteStreams() throws Exception {
        // The headers arrive after 2 s, slower than the 1.5 s global response timeout, so a 200 here also proves the
        // stream route is exempt from it. Then four 12-byte events, one every 2 s.
        String body = "data: ev01\n\ndata: ev02\n\ndata: ev03\n\ndata: ev04\n\n";
        BOOKING.respondWith(request -> new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream").addHeader("Cache-Control", "no-cache")
                .headersDelay(2, TimeUnit.SECONDS).body(body).throttleBody(12, 2, TimeUnit.SECONDS).build());

        WebTestClient slow = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        Flux<ServerSentEvent<String>> events = slow.get().uri("/api/bookings/events/7/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                }).getResponseBody();

        long start = System.nanoTime();
        List<String> received = new ArrayList<>();
        List<Long> arrivalMillis = new ArrayList<>();
        events.doOnNext(e -> {
            received.add(e.data());
            arrivalMillis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
        }).blockLast(Duration.ofSeconds(30));

        assertThat(received).containsExactly("ev01", "ev02", "ev03", "ev04");
        // incremental, not buffered: the first event is there about 6 s before the mock finishes
        assertThat(arrivalMillis.get(3) - arrivalMillis.get(0)).isGreaterThan(4000);
        // public: no token needed, and nothing identifies a caller
        assertThat(BOOKING.next().getHeaders().get("X-User-Id")).isNull();
    }

    @Test
    void notificationTicketsNeedUser() throws Exception {
        client.get().uri("/api/notifications/tickets/5").exchange().expectStatus().isUnauthorized();
        assertThat(NOTIFICATION.sawNothing()).isTrue();

        client.get().uri("/api/notifications/tickets/5").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-9")))
                .exchange().expectStatus().isOk();
        RecordedRequest seen = NOTIFICATION.next();
        assertThat(seen.getHeaders().get("X-User-Id")).isEqualTo("u-9");
    }

    @Test
    void authRoutes() throws Exception {
        // public
        client.post().uri("/api/auth/signup").header("X-Forwarded-For", "10.0.0.50").bodyValue("{}").exchange()
                .expectStatus().isOk();
        client.get().uri("/api/auth/.well-known/jwks.json").exchange().expectStatus().isOk();
        assertThat(AUTH.next().getTarget()).isEqualTo("/api/auth/signup");
        assertThat(AUTH.next().getTarget()).isEqualTo("/api/auth/.well-known/jwks.json");

        // /me needs a USER
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-42"))).exchange()
                .expectStatus().isOk();
        assertThat(AUTH.next().getHeaders().get("X-User-Id")).isEqualTo("u-42");

        // admin sub-tree needs ADMIN
        client.post().uri("/api/auth/admin/test-tokens").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-42")))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/auth/admin/test-tokens").header(HttpHeaders.AUTHORIZATION,
                bearer(adminToken("u-1"))).exchange().expectStatus().isOk();
        assertThat(AUTH.next().getTarget()).isEqualTo("/api/auth/admin/test-tokens");
    }
}
