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
import java.util.concurrent.atomic.AtomicReference;
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
import reactor.core.publisher.Mono;

/**
 * S4 edge abuse controls (SEC-09, SEC-10, SEC-11): the new buckets, the client address behind the trusted proxy, and
 * the fail-closed login limiter; and the signup and email-verification buckets (S5). Per-IP limits key on
 * {@code X-Forwarded-For} here because the test client connects over loopback, the trusted proxy in this context (as
 * nginx is in compose).
 */
class EdgeLimitsTest extends GatewayTestBase {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String REMAINING = "X-RateLimit-Remaining";
    private static final String VERIFY = "/api/auth/verify";
    private static final String RESEND = "/api/auth/verify/resend";
    /** The verify route's per-IP bucket, whose headers its responses carry: 30/min = 60 tokens, 2 a request. */
    private static final Countdown VERIFY_IP = new Countdown(60, 2);
    /** The resend route's per-IP bucket: 5/min = 60 tokens, 12 a request. */
    private static final Countdown RESEND_IP = new Countdown(60, 12);

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

    // ---- signup and email verification (S5) --------------------------------------------------------------------

    /** Signup has its own bucket, 3 a minute per IP: every signup sends an email. Login from that address still works. */
    @Test
    void signupIsThreePerMinutePerIpApartFromLogin() throws Exception {
        AtomicReference<String> address = new AtomicReference<>();
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(3, 20, (ip, attempt, request) -> {
            address.set(ip);
            return client.post().uri("/api/auth/signup").header("X-Forwarded-For", ip).bodyValue("{}");
        });
        // 20 s: one signup's 20 tokens at 1 token a second
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("20");
        client.post().uri("/api/auth/login").header("X-Forwarded-For", address.get()).bodyValue("{}").exchange()
                .expectStatus().isOk();
        // another address still signs up
        client.post().uri("/api/auth/signup").bodyValue("{}").exchange().expectStatus().isOk();
    }

    /** Login keeps its own 5/s, burst 10: using it up leaves signup from the same address alone. */
    @Test
    void loginIsTenAtOnceThenFivePerSecondPerIpApartFromSignup() throws Exception {
        AtomicReference<String> address = new AtomicReference<>();
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(10, 1, (ip, attempt, request) -> {
            address.set(ip);
            return client.post().uri("/api/auth/login").header("X-Forwarded-For", ip).bodyValue("{}");
        });
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        client.post().uri("/api/auth/signup").header("X-Forwarded-For", address.get()).bodyValue("{}").exchange()
                .expectStatus().isOk();
    }

    /**
     * Entering the emailed code: 10 a minute per user, on top of auth's own 5 guesses per code. The 11th try is
     * refused by the user's bucket (Retry-After 6: 6 tokens at 1 a second; the per-IP bucket would say 2).
     */
    @Test
    void verifyIsTenPerMinutePerUser() throws Exception {
        AtomicReference<String> address = new AtomicReference<>();
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(10, VERIFY_IP, (ip, attempt, request) -> {
            address.set(ip);
            return client.post().uri(VERIFY).header("X-Forwarded-For", ip)
                    .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-ver-" + attempt))).bodyValue("{}");
        });
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("6");
        // per user: another user at the same address is served
        client.post().uri(VERIFY).header("X-Forwarded-For", address.get())
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-ver-other"))).bodyValue("{}").exchange()
                .expectStatus().isOk();
    }

    /** And 30 a minute per IP, whoever is signed in (a new user for every request, so no user's bucket trips). */
    @Test
    void verifyIsAlsoThirtyPerMinutePerIp() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(30, 2, (ip, attempt, request) -> client.post()
                .uri(VERIFY).header("X-Forwarded-For", ip)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-vip-" + attempt + "-" + request)))
                .bodyValue("{}"));
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("2");
    }

    /** Asking for a new code sends an email: 3 a minute per user (auth adds 1 a minute, 5 an hour, 10 a day). */
    @Test
    void resendIsThreePerMinutePerUser() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(3, RESEND_IP, (ip, attempt, request) -> client
                .post().uri(RESEND).header("X-Forwarded-For", ip)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-res-" + attempt))).bodyValue("{}"));
        // the user's bucket: 20 tokens at 1 a second (the per-IP one would say 12)
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("20");
    }

    /** And 5 a minute per IP, whoever is signed in. */
    @Test
    void resendIsAlsoFivePerMinutePerIp() throws Exception {
        EntityExchangeResult<byte[]> denied = assertBurstThenLimited(5, 12, (ip, attempt, request) -> client.post()
                .uri(RESEND).header("X-Forwarded-For", ip)
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-rip-" + attempt + "-" + request)))
                .bodyValue("{}"));
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("12");
    }

    /** The JWKS and the gateway's own logout stay outside these buckets. */
    @Test
    void theRestOfAuthIsNotMeteredByTheseBuckets() throws Exception {
        client.get().uri("/api/auth/.well-known/jwks.json").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist(REMAINING);
        client.post().uri("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-out")))
                .exchange().expectStatus().isNoContent().expectHeader().doesNotExist(REMAINING);
        assertThat(AUTH.next().getTarget()).isEqualTo("/api/auth/.well-known/jwks.json");
        assertThat(AUTH.sawNothing()).as("logout is answered by the gateway, before any auth route").isTrue();
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

    /**
     * Only the limiter's script fails here (the token denylist's {@code hasKey} still answers), so this shows what
     * each bucket does on its own: login, signup and email verification fail closed, the rest fail open.
     */
    @Test
    void withTheLimiterDownLoginSignupAndEmailVerificationFailClosed() throws Exception {
        doReturn(Flux.error(new RedisConnectionFailureException("Redis is down"))).when(redis)
                .execute(any(RedisScript.class), anyList(), anyList());

        for (String path : List.of("/api/auth/login", "/api/auth/signup", VERIFY, RESEND)) {
            client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-limiter-down")))
                    .bodyValue("{}").exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "service-unavailable")
                    .jsonPath("$.requestId").isNotEmpty();
        }
        assertThat(AUTH.sawNothing()).isTrue();

        // every other bucket fails open, as documented in RouteConfig
        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-redis-down")))
                .bodyValue("{}").exchange().expectStatus().isOk();
    }

    /**
     * A real Redis outage also stops the token denylist (S7), which fails closed: every request with a bearer token
     * is 503, on fail-open buckets too. Anonymous reads keep working, unmetered.
     */
    @Test
    void withRedisDownOnlyAnonymousReadsKeepWorking() throws Exception {
        doReturn(Flux.error(new RedisConnectionFailureException("Redis is down"))).when(redis)
                .execute(any(RedisScript.class), anyList(), anyList());
        doReturn(Mono.error(new RedisConnectionFailureException("Redis is down"))).when(redis).hasKey(any());

        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-redis-down")))
                .bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.type").isEqualTo(PROBLEM + "service-unavailable");
        client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-redis-down")))
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(BOOKING.sawNothing()).isTrue();
        assertThat(AUTH.sawNothing()).isTrue();
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
        return assertBurstThenLimited(burst, new Countdown(burst * tokensPerRequest, tokensPerRequest), request);
    }

    /**
     * As above, for the first of a route's two buckets (per user, then per IP): the served requests carry the second
     * bucket's headers, {@code shown}, so the refill check reads that bucket's countdown. Every served request goes
     * through both buckets and both refill 1 token a second on Redis {@code TIME}, so a second boundary inside the
     * burst shows in that countdown. (Only a boundary falling between one request's two checks can hide, and it
     * gives the first bucket a single token, less than one of its requests costs: 6 for verify, 20 for resend.)
     */
    private EntityExchangeResult<byte[]> assertBurstThenLimited(int burst, Countdown shown, Attempt request)
            throws InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            String ip = freshClientIp();
            List<EntityExchangeResult<byte[]>> results = new ArrayList<>();
            for (int i = 0; i <= burst; i++) {
                results.add(request.build(ip, attempt, i).exchange().expectBody().returnResult());
            }
            if (refilledDuring(results, burst, shown)) {
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
        return fail("no attempt counted straight down to a 429: a token refill hit every attempt, or the route is not"
                + " limited as expected");
    }

    /** True unless the first {@code burst} answers are 200 with remaining tokens counting straight down. */
    private static boolean refilledDuring(List<EntityExchangeResult<byte[]>> results, int burst, Countdown shown) {
        for (int i = 0; i < burst; i++) {
            EntityExchangeResult<byte[]> r = results.get(i);
            assertThat(r.getStatus()).as("request %d of the burst", i + 1).isEqualTo(HttpStatus.OK);
            String remaining = r.getResponseHeaders().getFirst(REMAINING);
            if (remaining == null || Integer.parseInt(remaining) != shown.capacity() - shown.cost() * (i + 1)) {
                return true;
            }
        }
        return results.get(burst).getStatus() != HttpStatus.TOO_MANY_REQUESTS;
    }

    /** The bucket whose {@code X-RateLimit-Remaining} the responses show: its size in tokens and a request's cost. */
    private record Countdown(int capacity, int cost) {
    }

    @FunctionalInterface
    private interface Attempt {
        /** One request of the burst: {@code request} counts from 0 within the {@code attempt}. */
        WebTestClient.RequestHeadersSpec<?> build(String clientIp, int attempt, int request);
    }
}
