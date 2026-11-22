package dev.surge.auth.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * At most {@value #MAX_PER_DAY} verification emails per address per 24 hours, counted over signups and resends alike
 * and kept by address, not by account: replacing a stale unverified account, or signing up again, does not reset it.
 * Someone who registers an address that is not theirs can therefore make Surge email it ten times a day, no more.
 * Call inside a transaction (the row is locked until it ends).
 */
@Component
class VerificationMailQuota {

    static final int MAX_PER_DAY = EmailVerification.MAX_SENDS_PER_DAY;
    static final Duration WINDOW = EmailVerification.DAY_WINDOW;

    private final MailQuotaRepository rows;

    VerificationMailQuota(MailQuotaRepository rows) {
        this.rows = rows;
    }

    /** Counts one email to {@code email} and returns empty, or returns how long until the budget is available. */
    Optional<Duration> acquire(String email, Instant now) {
        rows.createIfMissing(email);
        MailQuota q = rows.lockByEmail(email).orElseThrow();
        Instant windowEnds = q.dayStartedAt().plus(WINDOW);
        if (!now.isBefore(windowEnds)) {
            q.startDay(now);
        } else if (q.sendsInDay() >= MAX_PER_DAY) {
            return Optional.of(Duration.between(now, windowEnds));
        }
        q.recordSend();
        return Optional.empty();
    }
}
