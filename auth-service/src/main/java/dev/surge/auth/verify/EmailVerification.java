package dev.surge.auth.verify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The one active email verification code of an account (S5, SEC-13), as a BCrypt hash, plus the counters that limit
 * wrong guesses and resends. The rules live here so they are unit-testable with fixed times:
 * <ul>
 *   <li>a code is valid {@value #CODE_LIFETIME_MINUTES} minutes and works once (the row is deleted on success);</li>
 *   <li>after {@value #MAX_ATTEMPTS} wrong codes it is locked: even the right code fails until a new one is sent;</li>
 *   <li>a new code may be sent at most once per 60 s, {@value #MAX_SENDS_PER_WINDOW} times per hour and
 *       {@value #MAX_SENDS_PER_DAY} times per 24 hours (the signup email counts), so an account registered with
 *       someone else's address can send that address at most 10 emails a day. A new code replaces the old one and
 *       resets the wrong-code count.</li>
 * </ul>
 * The counters belong to the account, not to a session: signing in again changes none of them.
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    static final int CODE_LIFETIME_MINUTES = 15;
    public static final Duration CODE_LIFETIME = Duration.ofMinutes(CODE_LIFETIME_MINUTES);
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration RESEND_GAP = Duration.ofSeconds(60);
    public static final Duration RESEND_WINDOW = Duration.ofHours(1);
    public static final int MAX_SENDS_PER_WINDOW = 5;
    public static final Duration DAY_WINDOW = Duration.ofHours(24);
    public static final int MAX_SENDS_PER_DAY = 10;

    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;
    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;
    @Column(name = "sends_in_window", nullable = false)
    private int sendsInWindow;
    @Column(name = "day_started_at", nullable = false)
    private Instant dayStartedAt;
    @Column(name = "sends_in_day", nullable = false)
    private int sendsInDay;

    protected EmailVerification() {
    }

    /** The first code of an account: also the first send of its hour and day windows. */
    public static EmailVerification first(long userId, String codeHash, Instant now) {
        EmailVerification v = new EmailVerification();
        v.userId = userId;
        v.windowStartedAt = now;
        v.sendsInWindow = 0;
        v.dayStartedAt = now;
        v.sendsInDay = 0;
        v.issue(codeHash, now);
        return v;
    }

    /** Empty when a new code may be sent at {@code now}; otherwise how long to wait (the longest applicable limit). */
    public Optional<Duration> resendWait(Instant now) {
        Instant dayEnds = dayStartedAt.plus(DAY_WINDOW);
        if (now.isBefore(dayEnds) && sendsInDay >= MAX_SENDS_PER_DAY) {
            return Optional.of(Duration.between(now, dayEnds));
        }
        Instant windowEnds = windowStartedAt.plus(RESEND_WINDOW);
        if (now.isBefore(windowEnds) && sendsInWindow >= MAX_SENDS_PER_WINDOW) {
            return Optional.of(Duration.between(now, windowEnds));
        }
        Instant gapEnds = lastSentAt.plus(RESEND_GAP);
        if (now.isBefore(gapEnds)) {
            return Optional.of(Duration.between(now, gapEnds));
        }
        return Optional.empty();
    }

    /** Replaces the code. Only after {@link #resendWait} was empty for {@code now}. */
    public void reissue(String newCodeHash, Instant now) {
        if (resendWait(now).isPresent()) {
            throw new IllegalStateException("A new code may not be sent yet");
        }
        if (!now.isBefore(windowStartedAt.plus(RESEND_WINDOW))) {
            windowStartedAt = now;
            sendsInWindow = 0;
        }
        if (!now.isBefore(dayStartedAt.plus(DAY_WINDOW))) {
            dayStartedAt = now;
            sendsInDay = 0;
        }
        issue(newCodeHash, now);
    }

    private void issue(String newCodeHash, Instant now) {
        codeHash = newCodeHash;
        expiresAt = now.plus(CODE_LIFETIME);
        failedAttempts = 0;
        lastSentAt = now;
        sendsInWindow++;
        sendsInDay++;
    }

    public boolean locked() {
        return failedAttempts >= MAX_ATTEMPTS;
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Counts a wrong code and returns how many attempts are left (0: now locked). */
    public int recordWrongCode() {
        failedAttempts++;
        return Math.max(0, MAX_ATTEMPTS - failedAttempts);
    }

    public Long getUserId() {
        return userId;
    }

    public String codeHash() {
        return codeHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
