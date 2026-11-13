package dev.surge.booking.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class RateLimiterTest {

    @Autowired RateLimiter limiter;
    @Autowired TestData testData;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
    }

    @Test
    void allowsBurstUpToCapacityThenBlocks() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryConsume("rl:t1", 10, 2.0).allowed()).isTrue();
        }
        RateDecision d = limiter.tryConsume("rl:t1", 10, 2.0);
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTime() throws Exception {
        for (int i = 0; i < 10; i++) {
            limiter.tryConsume("rl:t2", 10, 2.0);
        }
        assertThat(limiter.tryConsume("rl:t2", 10, 2.0).allowed()).isFalse();
        Thread.sleep(600);
        assertThat(limiter.tryConsume("rl:t2", 10, 2.0).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 10; i++) {
            limiter.tryConsume("rl:a", 10, 2.0);
        }
        assertThat(limiter.tryConsume("rl:a", 10, 2.0).allowed()).isFalse();
        assertThat(limiter.tryConsume("rl:b", 10, 2.0).allowed()).isTrue();
    }
}
