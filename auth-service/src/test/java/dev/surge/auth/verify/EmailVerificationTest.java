package dev.surge.auth.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The code rules with fixed times: 15 minutes, 5 wrong attempts, resend 1 per 60 s and 5 per hour. */
class EmailVerificationTest {

    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void aCodeLivesFifteenMinutes() {
        EmailVerification v = EmailVerification.first(1, "hash", T0);
        assertThat(v.expired(T0.plus(Duration.ofMinutes(15)).minusMillis(1))).isFalse();
        assertThat(v.expired(T0.plus(Duration.ofMinutes(15)))).isTrue();
    }

    @Test
    void theFifthWrongCodeLocksIt() {
        EmailVerification v = EmailVerification.first(1, "hash", T0);
        for (int left = 4; left >= 1; left--) {
            assertThat(v.recordWrongCode()).isEqualTo(left);
            assertThat(v.locked()).isFalse();
        }
        assertThat(v.recordWrongCode()).isZero();
        assertThat(v.locked()).isTrue();
        // a new code unlocks
        v.reissue("hash-2", T0.plusSeconds(60));
        assertThat(v.locked()).isFalse();
        assertThat(v.codeHash()).isEqualTo("hash-2");
        assertThat(v.expiresAt()).isEqualTo(T0.plusSeconds(60).plus(Duration.ofMinutes(15)));
    }

    @Test
    void resendWaitsSixtySecondsBetweenEmails() {
        EmailVerification v = EmailVerification.first(1, "hash", T0);
        assertThat(v.resendWait(T0)).contains(Duration.ofSeconds(60));
        assertThat(v.resendWait(T0.plusSeconds(59))).contains(Duration.ofSeconds(1));
        assertThat(v.resendWait(T0.plusSeconds(60))).isEmpty();
        assertThatThrownBy(() -> v.reissue("x", T0.plusSeconds(30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void atMostFiveEmailsPerHourIncludingTheSignupOne() {
        EmailVerification v = EmailVerification.first(1, "hash", T0);
        Instant t = T0;
        for (int i = 2; i <= 5; i++) {
            t = t.plusSeconds(60);
            assertThat(v.resendWait(t)).as("email %d", i).isEmpty();
            v.reissue("hash-" + i, t);
        }
        Instant sixth = t.plusSeconds(60);
        assertThat(v.resendWait(sixth)).contains(Duration.between(sixth, T0.plus(Duration.ofHours(1))));
        // a new window opens an hour after the first email
        assertThat(v.resendWait(T0.plus(Duration.ofHours(1)))).isEmpty();
        v.reissue("hash-6", T0.plus(Duration.ofHours(1)));
        assertThat(v.resendWait(T0.plus(Duration.ofHours(1)).plusSeconds(60))).isEmpty();
    }

    @Test
    void codesAreSixDigits() {
        for (int i = 0; i < 200; i++) {
            assertThat(EmailVerificationService.newCode()).matches("^[0-9]{6}$");
        }
    }
}
