package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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

        // on a USER route the forged value is overwritten by the token's roles, never forwarded
        client.post().uri("/api/bookings/holds")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-7")))
                .header("X-User-Roles", "ADMIN").header("x-user-roles", "USER,ADMIN").bodyValue("{}")
                .exchange().expectStatus().isOk();
        RecordedRequest seen = BOOKING.next();
        assertThat(seen.getHeaders().values("X-User-Roles")).containsExactly("USER");
        assertThat(seen.getHeaders().values("X-User-Id")).containsExactly("u-7");
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
    void loginAndSignupAreRateLimitedPerClientIp() throws Exception {
        List<EntityExchangeResult<byte[]>> mine = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            mine.add(client.post().uri(i % 2 == 0 ? "/api/auth/login" : "/api/auth/signup")
                    .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1").bodyValue("{}").exchange()
                    .expectBody().returnResult());
        }
        assertThat(mine.subList(0, 10)).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(HttpStatus.OK));
        EntityExchangeResult<byte[]> denied = mine.stream()
                .filter(r -> r.getStatus() == HttpStatus.TOO_MANY_REQUESTS).findFirst().orElseThrow();
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(denied.getResponseHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(new String(denied.getResponseBodyContent())).contains(PROBLEM + "rate-limited");

        // a different client IP has its own bucket; only the FIRST forwarded hop counts
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.99, 203.0.113.7").bodyValue("{}")
                .exchange().expectStatus().isOk();
        // the login limit does not touch other auth routes
        client.get().uri("/api/auth/.well-known/jwks.json").header("X-Forwarded-For", "203.0.113.7").exchange()
                .expectStatus().isOk();
    }
}
