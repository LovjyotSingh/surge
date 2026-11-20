package dev.surge.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.gateway.ratelimit.RateLimitFilter.Limit;
import dev.surge.gateway.ratelimit.RateLimitFilter.OnLimiterFailure;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {

    /** Fail-closed detection reads the limiter's remaining-tokens header, so a limiter without headers is refused. */
    @Test
    void aLimiterWithoutHeadersIsRefused() {
        RedisRateLimiter quiet = new RedisRateLimiter(1, 1);
        quiet.setIncludeHeaders(false);
        assertThatThrownBy(() -> new RateLimitFilter(quiet, null, "b", exchange -> Mono.just("k"),
                Limit.perSecond(1, 1), OnLimiterFailure.REFUSE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("includeHeaders");
    }

    @Test
    void theChainContinuesOffTheRedisReplyThread() {
        reactor.core.scheduler.Scheduler redisReplies = reactor.core.scheduler.Schedulers.newSingle("redis-reply");
        try {
            RedisRateLimiter repliesOnItsOwnThread = new RedisRateLimiter(1, 1) {
                @Override
                public Mono<org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response> isAllowed(
                        String routeId, String id) {
                    return Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(
                            true, java.util.Map.of(getRemainingHeader(), "4"))).publishOn(redisReplies);
                }
            };
            RateLimitFilter filter = new RateLimitFilter(repliesOnItsOwnThread, null, "b", exchange -> Mono.just("k"),
                    Limit.perSecond(1, 1));
            java.util.concurrent.atomic.AtomicReference<String> thread = new java.util.concurrent.atomic.AtomicReference<>();
            var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/api/bookings/holds"));

            filter.filter(exchange, ex -> {
                thread.set(Thread.currentThread().getName());
                return Mono.empty();
            }).block(java.time.Duration.ofSeconds(5));

            // The rest of the request never runs on the thread that delivered the Redis reply.
            assertThat(thread.get()).startsWith("parallel-");
        } finally {
            redisReplies.dispose();
        }
    }

    @Test
    void perMinuteLimitsAreWholeTokensPerSecond() {
        assertThat(Limit.perMinute(20)).isEqualTo(new Limit(1, 60, 3));
        assertThat(Limit.perMinute(20).retryAfterSeconds()).isEqualTo(3);
        assertThat(Limit.perSecond(2, 20).retryAfterSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> Limit.perMinute(7)).isInstanceOf(IllegalArgumentException.class);
    }
}
