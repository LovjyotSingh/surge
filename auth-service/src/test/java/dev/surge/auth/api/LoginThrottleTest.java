package dev.surge.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The per-account back-off (SEC-12) with a hand-moved clock. */
class LoginThrottleTest {

    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-16T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final LoginThrottle throttle = LoginThrottle.forTests(clock);

    @Test
    void fiveAttemptsGoThroughThenTheAccountWaitsThirtySeconds() {
        for (int i = 1; i <= 5; i++) {
            assertThat(throttle.tryAcquire("ada@example.com")).as("attempt %d", i).isEmpty();
        }
        assertThat(throttle.tryAcquire("ada@example.com")).contains(Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(10));
        assertThat(throttle.tryAcquire("ada@example.com")).contains(Duration.ofSeconds(20));
        // other accounts are untouched
        assertThat(throttle.tryAcquire("bob@example.com")).isEmpty();
    }

    @Test
    void theLockDoublesUpToFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            throttle.tryAcquire("ada@example.com");
        }
        Duration expected = Duration.ofSeconds(30);
        for (int round = 0; round < 8; round++) {
            Optional<Duration> wait = throttle.tryAcquire("ada@example.com");
            assertThat(wait).as("round %d", round).contains(expected);
            clock.advance(wait.orElseThrow());
            assertThat(throttle.tryAcquire("ada@example.com")).isEmpty();
            expected = expected.multipliedBy(2).compareTo(Duration.ofMinutes(15)) > 0 ? Duration.ofMinutes(15)
                    : expected.multipliedBy(2);
        }
        assertThat(LoginThrottle.lockFor(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottle.lockFor(9)).isEqualTo(Duration.ofSeconds(480));
        assertThat(LoginThrottle.lockFor(10)).isEqualTo(Duration.ofMinutes(15));
        assertThat(LoginThrottle.lockFor(1000)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aSuccessfulSignInClearsTheAccount() {
        for (int i = 0; i < 4; i++) {
            throttle.tryAcquire("ada@example.com");
        }
        throttle.succeeded("ada@example.com");
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.tryAcquire("ada@example.com")).isEmpty();
        }
        assertThat(throttle.tryAcquire("ada@example.com")).isPresent();
    }

    @Test
    void parallelGuessesCannotSlipPastTheCount() throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 200; i++) {
                pool.submit(() -> {
                    start.await();
                    if (throttle.tryAcquire("ada@example.com").isEmpty()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(allowed).hasValue(5);
    }
}
