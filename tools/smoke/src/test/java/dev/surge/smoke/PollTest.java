package dev.surge.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PollTest {

    @Test
    void returnsTheFirstNonNullAnswer() {
        AtomicInteger calls = new AtomicInteger();

        String got = Poll.until("thing", Duration.ofSeconds(5), Duration.ofMillis(10),
            () -> calls.incrementAndGet() < 3 ? null : "ready");

        assertThat(got).isEqualTo("ready");
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotWaitWhenTheFirstAttemptSucceeds() {
        long t0 = System.nanoTime();
        Poll.until("thing", Duration.ofSeconds(5), Duration.ofSeconds(2), () -> "now");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1_000);
    }

    @Test
    void failsAtTheDeadlineNamingWhatItWaitedFor() {
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> Poll.until("a ticket email", Duration.ofMillis(300), Duration.ofMillis(50),
            () -> null))
            .isInstanceOf(CheckFailure.class)
            .hasMessageContaining("a ticket email")
            .hasMessageContaining("within");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(elapsedMs).isBetween(250L, 5_000L);
    }

    @Test
    void theLastNoteExplainsWhyItWasNotReady() {
        AtomicInteger n = new AtomicInteger();

        assertThatThrownBy(() -> Poll.until("seatsLeft", Duration.ofMillis(200), Duration.ofMillis(20),
            () -> {
                Poll.note("still " + (100 - n.incrementAndGet()));
                return null;
            }))
            .isInstanceOf(CheckFailure.class)
            .hasMessageContaining("last seen: still ");
    }

    @Test
    void aFailureThrownByTheAttemptEndsThePollImmediately() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> Poll.until("x", Duration.ofSeconds(5), Duration.ofMillis(10), () -> {
            calls.incrementAndGet();
            throw new CheckFailure("hard failure");
        })).isInstanceOf(CheckFailure.class).hasMessage("hard failure");
        assertThat(calls).hasValue(1);
    }
}
