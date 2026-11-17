package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import dev.surge.gateway.support.GatewayTestBase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** SEC-18: audience, jti, logout through the Redis denylist, and the key-set refetch cooldown. */
class TokenLifecycleTest extends GatewayTestBase {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String ME = "/api/auth/me";
    private static final String LOGOUT = "/api/auth/logout";

    @Autowired
    StringRedisTemplate redis;

    private static String tokenWith(String sub, Map<String, Object> claims) {
        return token(sub, sub + "@surge.test", claims, List.of("USER"), ISSUER,
                Instant.now().plus(30, ChronoUnit.MINUTES), KEY);
    }

    private static Map<String, Object> claim(String name, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(name, value);
        return map;
    }

    private static String jti(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
    }

    @Test
    void aTokenNotMeantForTheApiIs401() throws Exception {
        for (Object aud : new Object[] {null, "other-api", List.of("other-api", "auth-admin"), List.of()}) {
            client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("u-aud", claim("aud", aud))))
                    .exchange().expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        }
        assertThat(AUTH.sawNothing()).isTrue();

        // aud may hold other audiences as well, and a single string is the same as a one-element array
        for (Object aud : new Object[] {List.of("other-api", "surge-api"), "surge-api"}) {
            client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("u-aud", claim("aud", aud))))
                    .exchange().expectStatus().isOk();
            assertThat(AUTH.next()).isNotNull();
        }
    }

    @Test
    void aTokenWithoutAUsableJtiIs401() throws Exception {
        for (Object jti : new Object[] {null, "short", "has spaces in it", "x".repeat(65)}) {
            client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("u-jti", claim("jti", jti))))
                    .exchange().expectStatus().isUnauthorized();
        }
        assertThat(AUTH.sawNothing()).isTrue();
    }

    @Test
    void afterLogoutTheSameTokenIs401WithinOneSecond() throws Exception {
        String token = userToken("u-logout");
        String otherSession = userToken("u-logout");
        client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange().expectStatus().isOk();
        assertThat(AUTH.next()).isNotNull();

        client.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id");
        long loggedOutAt = System.nanoTime();
        client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "unauthorized");
        assertThat(Duration.ofNanos(System.nanoTime() - loggedOutAt)).isLessThan(Duration.ofSeconds(1));

        // everywhere, not just on auth's routes
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(token)).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                .expectStatus().isUnauthorized();
        assertThat(AUTH.sawNothing()).as("logout is answered by the gateway, never routed to auth").isTrue();
        assertThat(BOOKING.sawNothing()).isTrue();

        // the same user's other token (another jti) is untouched
        client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(otherSession)).exchange()
                .expectStatus().isOk();
    }

    @Test
    void theDenylistEntryLivesAsLongAsTheTokenCouldStillBeAccepted() throws Exception {
        String token = token("u-ttl", "u-ttl@surge.test", List.of("USER"), ISSUER,
                Instant.now().plus(10, ChronoUnit.MINUTES), KEY);
        client.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange()
                .expectStatus().isNoContent();
        String key = "surge:jwt:revoked:" + jti(token);
        Long ttl = redis.getExpire(key);
        // exp in 10 min plus the decoder's 60 s clock skew; only the jti is stored, never the token
        assertThat(ttl).isBetween(600L, 660L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
    }

    @Test
    void logoutNeedsAValidTokenAndOnlyPost() throws Exception {
        client.post().uri(LOGOUT).exchange().expectStatus().isUnauthorized();
        client.post().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer("garbage")).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri(LOGOUT).header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-get"))).exchange()
                .expectStatus().isForbidden();
        client.delete().uri(LOGOUT).exchange().expectStatus().isUnauthorized();
        assertThat(AUTH.sawNothing()).isTrue();
    }

    @Test
    void aHundredRandomKidTokensFetchTheKeySetAtMostOncePer30Seconds() throws Exception {
        var original = JWKS.currentHandler();
        AtomicInteger fetches = new AtomicInteger();
        JWKS.respondWith(request -> {
            fetches.incrementAndGet();
            return original.apply(request);
        });
        try {
            jwksClock.advance(Duration.ofSeconds(31));
            IntStream.range(0, 100).parallel().forEach(i -> {
                RSAKey randomKid = new RSAKey.Builder(UNKNOWN_KEY).keyID(UUID.randomUUID().toString()).build();
                String forged = token("u-kid", "u-kid@surge.test", List.of("USER"), ISSUER,
                        Instant.now().plus(30, ChronoUnit.MINUTES), randomKid);
                client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(forged)).exchange()
                        .expectStatus().isUnauthorized();
            });
            assertThat(fetches).hasValue(1);

            // real tokens still verify from the cache
            client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-kid"))).exchange()
                    .expectStatus().isOk();
            assertThat(fetches).hasValue(1);

            // the next window allows exactly one more fetch
            jwksClock.advance(Duration.ofSeconds(31));
            for (int i = 0; i < 5; i++) {
                RSAKey randomKid = new RSAKey.Builder(UNKNOWN_KEY).keyID(UUID.randomUUID().toString()).build();
                client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(token("u-kid", "u-kid@surge.test",
                        List.of("USER"), ISSUER, Instant.now().plus(30, ChronoUnit.MINUTES), randomKid)))
                        .exchange().expectStatus().isUnauthorized();
            }
            assertThat(fetches).hasValue(2);
        } finally {
            JWKS.respondWith(original);
        }
    }

    @Test
    void theMeResponseIsStillAuthsOwn() {
        // sanity: the local logout route did not shadow anything else under /api/auth
        String body = new String(client.get().uri(ME).header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-me")))
                .exchange().expectStatus().isEqualTo(HttpStatus.OK).expectBody().returnResult().getResponseBody());
        assertThat((Boolean) JsonPath.read(body, "$.ok")).isTrue();
    }
}
