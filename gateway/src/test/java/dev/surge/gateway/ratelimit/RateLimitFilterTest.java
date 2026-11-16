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
    void perMinuteLimitsAreWholeTokensPerSecond() {
        assertThat(Limit.perMinute(20)).isEqualTo(new Limit(1, 60, 3));
        assertThat(Limit.perMinute(20).retryAfterSeconds()).isEqualTo(3);
        assertThat(Limit.perSecond(2, 20).retryAfterSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> Limit.perMinute(7)).isInstanceOf(IllegalArgumentException.class);
    }
}
