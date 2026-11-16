package dev.surge.auth.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-account sign-in throttle (S5, SEC-12), keyed on the normalised email, whether or not an account exists, so
 * the answer never tells a registered address from an unknown one. It complements the gateway's per-IP limit: a
 * botnet rotating IPs still gets only a few guesses per account.
 *
 * <ul>
 *   <li>Every attempt that is let through counts as a failure up front ({@link #tryAcquire}); a successful sign-in
 *       clears the account ({@link #succeeded}). Counting first means parallel guesses cannot all slip in before the
 *       first failure is recorded.</li>
 *   <li>The {@value #FREE_ATTEMPTS}th attempt starts a lock of 30 s; each later attempt (once the lock is over)
 *       doubles it, up to 15 minutes. While locked, nothing is checked (not even a correct password), so locked
 *       answers cost the same for every email: no BCrypt, same 429.</li>
 *   <li>An account untouched for an hour is forgotten.</li>
 * </ul>
 *
 * <p>State lives in this process (Caffeine, at most {@value #MAX_ACCOUNTS} accounts) because auth runs as a single
 * instance. When auth scales out, this must move to Redis (one counter and lock per email, e.g. a hash with a TTL)
 * so every instance sees the same count.
 */
@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    static final Duration BASE_LOCK = Duration.ofSeconds(30);
    static final Duration MAX_LOCK = Duration.ofMinutes(15);
    static final Duration FORGET_AFTER = Duration.ofHours(1);
    static final long MAX_ACCOUNTS = 100_000;

    private record State(int attempts, Instant lockedUntil) {
    }

    private final Cache<String, State> accounts;
    private final Clock clock;

    @Autowired
    LoginThrottle(Clock clock) {
        this(clock, Caffeine.newBuilder().expireAfterWrite(FORGET_AFTER).maximumSize(MAX_ACCOUNTS).build());
    }

    private LoginThrottle(Clock clock, Cache<String, State> accounts) {
        this.clock = clock;
        this.accounts = accounts;
    }

    static LoginThrottle forTests(Clock clock) {
        return new LoginThrottle(clock, Caffeine.newBuilder().build());
    }

    /**
     * Lets one sign-in attempt for {@code email} (already normalised) through and counts it: empty when allowed;
     * otherwise how long the account stays locked.
     */
    public Optional<Duration> tryAcquire(String email) {
        Instant now = clock.instant();
        Duration[] wait = new Duration[1];
        accounts.asMap().compute(email, (key, state) -> {
            State current = state == null ? new State(0, null) : state;
            if (current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
                wait[0] = Duration.between(now, current.lockedUntil());
                return current;
            }
            int attempts = current.attempts() + 1;
            return new State(attempts, attempts >= FREE_ATTEMPTS ? now.plus(lockFor(attempts)) : null);
        });
        return Optional.ofNullable(wait[0]);
    }

    /** The account signed in: its count and lock are cleared. */
    public void succeeded(String email) {
        accounts.invalidate(email);
    }

    /** 30 s for the 5th attempt, doubling with each later one, at most 15 minutes. */
    static Duration lockFor(int attempts) {
        int doublings = Math.min(attempts - FREE_ATTEMPTS, 10);
        Duration lock = BASE_LOCK.multipliedBy(1L << doublings);
        return lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock;
    }
}
