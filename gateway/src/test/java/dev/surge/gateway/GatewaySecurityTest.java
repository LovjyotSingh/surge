package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.common.security.InternalIdentity;
import dev.surge.gateway.support.GatewayTestBase;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** Attack-shaped requests: forged tokens and headers, odd paths, hidden services. Nothing here may reach a service. */
class GatewaySecurityTest extends GatewayTestBase {

    private static final String PROBLEM = "https://surge.dev/problems/";

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        String sameKid = token("u-5", "u-5@surge.test", List.of("USER", "ADMIN"), ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS), OTHER_KEY);
        String unknownKid = token("u-5", "u-5@surge.test", List.of("USER", "ADMIN"), ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS), UNKNOWN_KEY);
        for (String forged : List.of(sameKid, unknownKid)) {
            client.post().uri("/api/bookings/admin/reset").header(HttpHeaders.AUTHORIZATION, bearer(forged))
                    .exchange().expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        }
        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void unsignedAndGarbageTokensAre401() throws Exception {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"u-5\",\"iss\":\"surge-auth\",\"roles\":[\"ADMIN\"],\"exp\":4102444800}".getBytes());
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes());
        for (String bad : List.of(header + "." + payload + ".", "garbage", "a.b.c")) {
            client.post().uri("/api/bookings/admin/reset").header(HttpHeaders.AUTHORIZATION, bearer(bad)).exchange()
                    .expectStatus().isUnauthorized();
        }
        // not a bearer scheme at all
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, "Basic dTpw").exchange()
                .expectStatus().isUnauthorized();
        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void tokenWithoutExpiryOrWithUnsafeSubjectIs401() throws Exception {
        String noExp = token("u-5", "u-5@surge.test", List.of("USER"), ISSUER, null, KEY);
        String badSub = token("u-5\r\nX-Evil: 1", "x@surge.test", List.of("USER"), ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS), KEY);
        String longSub = token("u".repeat(65), "x@surge.test", List.of("USER"), ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS), KEY);
        for (String bad : List.of(noExp, badSub, longSub)) {
            client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(bad))
                    .bodyValue("{}").exchange().expectStatus().isUnauthorized();
        }
        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void loadtestSubjectsAndTokensAre401UnlessAllowed() throws Exception {
        Instant exp = Instant.now().plus(15, ChronoUnit.MINUTES);
        String ltSubject = token("lt-x-1", "lt-x-1@loadtest.local", List.of("USER"), ISSUER, exp, KEY);
        String ltClaim = token("u-5", "u-5@surge.test", java.util.Map.of("lt", true), List.of("USER"), ISSUER, exp,
                KEY);
        String ltString = token("u-5", "u-5@surge.test", java.util.Map.of("lt", "true"), List.of("USER"), ISSUER, exp,
                KEY);
        for (String bad : List.of(ltSubject, ltClaim, ltString)) {
            client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(bad))
                    .bodyValue("{}").exchange().expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        }
        assertThat(BOOKING.sawNothing()).isTrue();
        // a permitAll route still validates a presented token: an lt token is 401 there too
        for (String bad : List.of(ltSubject, ltClaim, ltString)) {
            client.get().uri("/api/catalog/events").header(HttpHeaders.AUTHORIZATION, bearer(bad)).exchange()
                    .expectStatus().isUnauthorized();
        }
        assertThat(CATALOG.sawNothing()).isTrue();
    }

    @Test
    void lookalikeOrMalformedRolesDoNotGrantAdmin() throws Exception {
        List<Object> notAdmin = List.of(List.of("ADMINISTRATOR"), "admin", "ADMIN", List.of("admin"),
                List.of("ROLE_ADMIN"), List.of("USER", " ADMIN"), List.of("ADMIN,USER"), List.of(1, 2));
        for (Object roles : notAdmin) {
            client.post().uri("/api/bookings/admin/reset")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRoles("u-6", roles))).exchange()
                    .expectStatus().isForbidden()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "forbidden");
        }
        // a token without any roles claim is authenticated but is not even a USER
        client.post().uri("/api/bookings/holds")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithRoles("u-6", null))).bodyValue("{}").exchange()
                .expectStatus().isForbidden();
        assertThat(BOOKING.sawNothing()).isTrue();
    }

    @Test
    void forgedRolesHeaderWithUserTokenIs403AndNeverReachesDownstream() throws Exception {
        client.post().uri("/api/bookings/admin/reset")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-7")))
                .header("X-User-Roles", "ADMIN").header("X-User-Id", "u-admin")
                .exchange().expectStatus().isForbidden();
        assertThat(BOOKING.sawNothing()).isTrue();

        // on a USER route the forged values are dropped, never forwarded: the signed token carries the token's roles
        client.post().uri("/api/bookings/holds")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-7")))
                .header("X-User-Roles", "ADMIN").header("x-user-roles", "USER,ADMIN").bodyValue("{}")
                .exchange().expectStatus().isOk();
        RecordedRequest seen = BOOKING.next();
        InternalIdentity identity = identityFor(seen, "booking-service");
        assertThat(identity.subject()).isEqualTo("u-7");
        assertThat(identity.roles()).containsExactly("USER");
    }

    @Test
    void paymentsAreHiddenExceptAdmin() throws Exception {
        for (String token : List.of(userToken("u-8"), adminToken("u-admin"))) {
            client.get().uri("/api/payments/charge").header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "not-found");
        }
        client.post().uri("/api/payments/charge").bodyValue("{}").exchange().expectStatus().isNotFound();
        client.post().uri("/api/payments").bodyValue("{}").exchange().expectStatus().isNotFound();
        assertThat(PAYMENT.sawNothing()).isTrue();

        client.get().uri("/api/payments/admin/chaos").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-8")))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/payments/admin/chaos").header(HttpHeaders.AUTHORIZATION,
                bearer(adminToken("u-admin"))).exchange().expectStatus().isOk();
        assertThat(PAYMENT.next().getTarget()).isEqualTo("/api/payments/admin/chaos");
    }

    @Test
    void dailySalesStatsAreAdminOnly() throws Exception {
        String uri = "/api/catalog/admin/stats/daily?eventId=1";
        client.get().uri(uri).exchange().expectStatus().isUnauthorized();
        client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-8"))).exchange()
                .expectStatus().isForbidden();
        assertThat(CATALOG.sawNothing()).isTrue();
        client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(adminToken("u-admin"))).exchange()
                .expectStatus().isOk();
        RecordedRequest seen = CATALOG.next();
        assertThat(seen.getTarget()).isEqualTo(uri);
        assertThat(identityFor(seen, "catalog-service").isGatewayAdmin()).isTrue();
    }

    @Test
    void pathTricksCannotSmuggleAnAdminPathThroughAPublicRule() throws Exception {
        for (String path : List.of("/api/catalog/x/../admin/events", "/api/catalog/x/%2e%2e/admin/events",
                "/api/catalog/x/%2E%2E/admin/events", "/api/catalog/x%2f..%2fadmin/events",
                "/api/catalog/x/..%5cadmin", "/api/catalog//admin/events", "/api/catalog;a=b/admin/events",
                "/api/auth/./admin/test-tokens", "/api/bookings/events/1/../../admin/reset")) {
            client.get().uri(URI.create("http://localhost:" + port + path)).exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "bad-request");
        }
        assertThat(CATALOG.sawNothing()).isTrue();
        assertThat(BOOKING.sawNothing()).isTrue();
        assertThat(AUTH.sawNothing()).isTrue();
    }

    @Test
    void everythingElseIsDenied() throws Exception {
        // unknown paths, wrong methods on public prefixes, internal endpoints, the fallback target
        client.get().uri("/api/unknown").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/users/u-1").exchange().expectStatus().isUnauthorized();
        client.get().uri("/fallback/catalog").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/catalog/events").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/bookings/events/1").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/notifications/tickets/1").header(HttpHeaders.AUTHORIZATION,
                bearer(userToken("u-8"))).bodyValue("{}").exchange().expectStatus().isForbidden();
        client.get().uri("/api/unknown").header(HttpHeaders.AUTHORIZATION, bearer(adminToken("u-admin"))).exchange()
                .expectStatus().isForbidden();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        assertThat(CATALOG.sawNothing()).isTrue();
        assertThat(BOOKING.sawNothing()).isTrue();
        assertThat(NOTIFICATION.sawNothing()).isTrue();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void loginIsRateLimitedPerClientIp() throws Exception {
        List<EntityExchangeResult<byte[]>> mine = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            mine.add(client.post().uri("/api/auth/login")
                    .header("X-Forwarded-For", "10.0.0.1, 203.0.113.7").bodyValue("{}").exchange()
                    .expectBody().returnResult());
        }
        assertThat(mine.subList(0, 10)).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(HttpStatus.OK));
        EntityExchangeResult<byte[]> denied = mine.stream()
                .filter(r -> r.getStatus() == HttpStatus.TOO_MANY_REQUESTS).findFirst().orElseThrow();
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(denied.getResponseHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(new String(denied.getResponseBodyContent())).contains(PROBLEM + "rate-limited");

        // a different client IP has its own bucket; only the LAST forwarded hop counts
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.7, 203.0.113.99").bodyValue("{}")
                .exchange().expectStatus().isOk();
        // the login limit does not touch other auth routes: signup has its own bucket (EdgeLimitsTest), the JWKS none
        client.post().uri("/api/auth/signup").header("X-Forwarded-For", "203.0.113.7").bodyValue("{}").exchange()
                .expectStatus().isOk();
        client.get().uri("/api/auth/.well-known/jwks.json").header("X-Forwarded-For", "203.0.113.7").exchange()
                .expectStatus().isOk();
    }

    @Test
    void rotatingTheFirstForwardedHopDoesNotEscapeTheLimit() throws Exception {
        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            statuses.add((HttpStatus) client.post().uri("/api/auth/login")
                    .header("X-Forwarded-For", "10.9." + i + ".1, 198.51.100.5").bodyValue("{}").exchange()
                    .expectBody().returnResult().getStatus());
        }
        assertThat(statuses.subList(0, 10)).containsOnly(HttpStatus.OK);
        assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void ipBucketIsKeyedOnTheLastHop() throws Exception {
        // exhaust the bucket of 9.9.9.9 directly ...
        for (int i = 0; i < 16; i++) {
            client.post().uri("/api/auth/login").header("X-Forwarded-For", "9.9.9.9").bodyValue("{}").exchange()
                    .expectBody().returnResult();
        }
        // ... a request that merely lists it as the last hop shares that bucket and is refused,
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "1.1.1.1, 9.9.9.9").bodyValue("{}")
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // while a request with 9.9.9.9 only as a (forgeable) earlier hop is a different client
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "9.9.9.9, 1.1.1.1").bodyValue("{}")
                .exchange().expectStatus().isOk();
    }

    /**
     * nginx streams the CSV import to the gateway without buffering it ({@code proxy_request_buffering off}), so an
     * anonymous upload must be refused before its body is read: here only 1 KB of a declared 6 MB ever arrives, and
     * the answer still comes at once. A USER token is refused the same way. Catalog sees nothing.
     */
    @Test
    void anUploadIsRefusedBeforeItsBodyIsRead() throws Exception {
        assertThat(statusOfPartialUpload(null)).isEqualTo(401);
        assertThat(statusOfPartialUpload(userToken("u-upload"))).isEqualTo(403);
        assertThat(CATALOG.sawNothing()).isTrue();
    }

    /** The status line's code for an import whose body stops after 1 KB of 6 MB; fails if none comes within 5 s. */
    private int statusOfPartialUpload(String token) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            String head = "POST /api/catalog/admin/events/import HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "X-Forwarded-For: " + freshClientIp() + "\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                    + "Content-Type: multipart/form-data; boundary=surge\r\n"
                    + "Content-Length: 6000000\r\n\r\n";
            var out = socket.getOutputStream();
            out.write(head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(new byte[1024]);
            out.flush();
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            assertThat(statusLine).as("the gateway answered without the rest of the body").startsWith("HTTP/1.1 ");
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }

    @Test
    void unreachableOrHangingJwksFailsClosedWithin3Seconds() throws Exception {
        // an unknown kid forces a fetch of the key set (once the 30 s cooldown has passed); the mock answers after 5 s,
        // the fetch gives up after 2 s
        jwksClock.advance(java.time.Duration.ofSeconds(31));
        var original = JWKS.currentHandler();
        JWKS.respondWith(request -> new mockwebserver3.MockResponse.Builder().code(200)
                .headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).body("{\"keys\":[]}").build());
        try {
            long start = System.nanoTime();
            client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(token("u-j",
                    "u-j@surge.test", List.of("USER"), ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), UNKNOWN_KEY)))
                    .bodyValue("{}").exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "auth-unavailable");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(3));
            assertThat(BOOKING.sawNothing()).isTrue();
        } finally {
            JWKS.respondWith(original);
        }
    }
}
