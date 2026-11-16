package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

import dev.surge.gateway.support.GatewayTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/**
 * S4 edge abuse controls (SEC-09, SEC-10, SEC-11): the new buckets, the client address behind the trusted proxy, and
 * the fail-closed login limiter. Per-IP limits key on {@code X-Forwarded-For} here because the test client connects
 * over loopback, the trusted proxy in this context (as nginx is in compose).
 */
class EdgeLimitsTest extends GatewayTestBase {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String REMAINING = "X-RateLimit-Remaining";

    /** The limiter's Redis template: a spy delegating to the real one (see IntegrationTest). */
    @Autowired
    ReactiveStringRedisTemplate redis;

    @Autowired
    Environment environment;

    // ---- the buckets -----------------------------------------------------------------------------------------

    @Test
    void theTwentyFirstSearchInOneSecondFromOneIpIs429WithRetryAfter() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(20, 1, (ip, attempt, request) ->
                client.get().uri("/api/catalog/events/search?q=neon").header("X-Forwarded-For", ip));
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        // a different client still searches
        client.get().uri("/api/catalog/events/search?q=neon").exchange().expectStatus().isOk();
    }

    @Test
    void catalogListAndDetailShareThePerIpBucket() throws Exception {
        assertBurstThenLimited(20, 1, (ip, attempt, request) -> client.get()
                .uri(request % 2 == 0 ? "/api/catalog/events" : "/api/catalog/events/1")
                .header("X-Forwarded-For", ip));
    }

    @Test
    void bookingEventReadsAre20PerSecondPerIp() throws Exception {
        assertBurstThenLimited(40, 1, (ip, attempt, request) ->
                client.get().uri("/api/bookings/events/1").header("X-Forwarded-For", ip));
    }

    /**
     * Opening a seat stream: 2 per second per IP, bursts of 20 (a CGNAT or office address opens many at sale
     * start). The limit is checked once per request, so the events of an open stream are never throttled
     * (sseRouteStreams in GatewayRoutingTest; and below, 30 events in one go).
     */
    @Test
    void openingSeatStreamsIsLimitedPerIpButTheirEventsAreNot() throws Exception {
        assertBurstThenLimited(20, 1, (ip, attempt, request) ->
                client.get().uri("/api/bookings/events/7/stream").header("X-Forwarded-For", ip));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            many.append("data: e").append(i).append("\n\n");
        }
        BOOKING.respondWith(request -> new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream").body(many.toString()).build());
        List<ServerSentEvent<String>> events = client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/bookings/events/7/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                }).getResponseBody().collectList().block(Duration.ofSeconds(10));
        assertThat(events).hasSize(30);
    }

    @Test
    void ticketReadsAreOnePerSecondPerUserBurstFive() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(5, 1, (ip, attempt, request) -> client.get()
                .uri("/api/notifications/tickets/5").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-tix-"
                        + attempt))));
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        // per user, not per address: another user from the same address is served
        client.get().uri("/api/notifications/tickets/5").header(HttpHeaders.AUTHORIZATION,
                bearer(userToken("u-tix-other"))).exchange().expectStatus().isOk();
    }

    @Test
    void meIsTwoPerSecondPerUser() throws Exception {
        assertBurstThenLimited(4, 1, (ip, attempt, request) -> client.get().uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-me-" + attempt))));
        client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-me-other")))
                .exchange().expectStatus().isOk();
    }

    /**
     * On top of the per-user bucket: 20 holds a minute per IP, whoever is signed in (a new user for every request
     * here, so the per-user bucket never trips). The IP bucket's headers are the response's (it runs second).
     */
    @Test
    void holdsAreAlso20PerMinutePerIp() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(20, 3, (ip, attempt, request) -> client.post()
                .uri("/api/bookings/holds").header("X-Forwarded-For", ip)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-hip-" + attempt + "-" + request)))
                .bodyValue("{}"));
        // 3 s: one request's 3 tokens at 1 token a second, so it was the per-IP bucket (per user would say 1)
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("3");
        // another address still holds
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-hip-other")))
                .bodyValue("{}").exchange().expectStatus().isOk();
    }

    // ---- client address ----------------------------------------------------------------------------------------

    @Test
    void ipv6ClientsShareOneBucketPerSlash64() throws Exception {
        // every request a different address of the attempt's own /64
        assertBurstThenLimited(10, 1, (ip, attempt, request) -> client.post().uri("/api/auth/login")
                .header("X-Forwarded-For", "2001:db8:5:" + Integer.toHexString(0x100 + attempt) + ":"
                        + Integer.toHexString(request + 1) + "::1")
                .bodyValue("{}"));
        // the next /64 is another client
        client.post().uri("/api/auth/login").header("X-Forwarded-For", "2001:db8:5:7::1").bodyValue("{}").exchange()
                .expectStatus().isOk();
    }

    /** The rate limiter and Spring Cloud Gateway's forwarded-header handling trust the very same proxies. */
    @Test
    void gatewayForwardingTrustsTheSameProxies() {
        assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.trusted-proxies"))
                .isEqualTo(environment.getProperty("surge.security.trusted-proxies"))
                .isEqualTo("127[.]0[.]0[.]1|0:0:0:0:0:0:0:1");
    }

    // ---- Redis down ----------------------------------------------------------------------------------------

    @Test
    void withRedisDownLoginAndSignupFailClosed() throws Exception {
        doReturn(Flux.error(new RedisConnectionFailureException("Redis is down"))).when(redis)
                .execute(any(RedisScript.class), anyList(), anyList());

        for (String path : List.of("/api/auth/login", "/api/auth/signup")) {
            client.post().uri(path).bodyValue("{}").exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "service-unavailable")
                    .jsonPath("$.requestId").isNotEmpty();
        }
        assertThat(AUTH.sawNothing()).isTrue();

        // every other bucket fails open, as documented in RouteConfig: reads keep working without Redis
        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-redis-down")))
                .bodyValue("{}").exchange().expectStatus().isOk();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /**
     * Sends {@code burst + 1} requests from one fresh client and asserts the first {@code burst} are served and the
     * next one is a 429 {@code rate-limited} problem. Gateway's limiter refills whole seconds' worth of tokens at
     * once (Redis {@code TIME} seconds), so a burst that straddles a second boundary gets extra tokens; the
     * {@code X-RateLimit-Remaining} countdown shows when that happened, and the burst is then retried with a new
     * client. {@code request} gets the client address and the attempt number (for per-user buckets).
     */
    private EntityExchangeResult<byte[]> assertBurstThenLimited(int burst, int tokensPerRequest,
            Attempt request) throws InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            String ip = freshClientIp();
            List<EntityExchangeResult<byte[]>> results = new ArrayList<>();
            for (int i = 0; i <= burst; i++) {
                results.add(request.build(ip, attempt, i).exchange().expectBody().returnResult());
            }
            if (refilledDuring(results, burst, tokensPerRequest)) {
                TimeUnit.MILLISECONDS.sleep(50);
                continue;
            }
            EntityExchangeResult<byte[]> denied = results.get(burst);
            assertThat(denied.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(denied.getResponseHeaders().getContentType().isCompatibleWith(
                    MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
            assertThat(new String(denied.getResponseBodyContent())).contains(PROBLEM + "rate-limited")
                    .contains("requestId");
            return denied;
        }
        return fail("a token refill hit every attempt");
    }

    /** True unless the first {@code burst} answers are 200 with remaining tokens counting straight down. */
    private static boolean refilledDuring(List<EntityExchangeResult<byte[]>> results, int burst, int cost) {
        int capacity = burst * cost;
        for (int i = 0; i < burst; i++) {
            EntityExchangeResult<byte[]> r = results.get(i);
            assertThat(r.getStatus()).as("request %d of the burst", i + 1).isEqualTo(HttpStatus.OK);
            String remaining = r.getResponseHeaders().getFirst(REMAINING);
            if (remaining == null || Integer.parseInt(remaining) != capacity - cost * (i + 1)) {
                return true;
            }
        }
        return results.get(burst).getStatus() != HttpStatus.TOO_MANY_REQUESTS;
    }

    @FunctionalInterface
    private interface Attempt {
        /** One request of the burst: {@code request} counts from 0 within the {@code attempt}. */
        WebTestClient.RequestHeadersSpec<?> build(String clientIp, int attempt, int request);
    }
}
