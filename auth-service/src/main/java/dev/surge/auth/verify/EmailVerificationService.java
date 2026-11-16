package dev.surge.auth.verify;

import dev.surge.auth.api.UserView;
import dev.surge.auth.api.UserViews;
import dev.surge.auth.error.AccountProblem;
import dev.surge.auth.error.UserNotFoundException;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email verification with a 6-digit code (S5, SEC-13). Signup leaves the account unverified and emails a code; the
 * signed-in user enters it ({@link #verify}) or asks for a new one ({@link #resend}). Codes are random, stored only as
 * a BCrypt hash, and checked under a row lock so parallel guesses are counted one by one. The rules (15 minutes,
 * single use, 5 wrong attempts, 1 resend per 60 s and 5 per hour) are {@link EmailVerification}'s.
 */
@Service
public class EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final EmailVerificationRepository codes;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final VerificationMailer mailer;
    private final Clock clock;

    EmailVerificationService(UserRepository users, EmailVerificationRepository codes, PasswordEncoder encoder,
                             TransactionTemplate tx, VerificationMailer mailer, Clock clock) {
        this.users = users;
        this.codes = codes;
        this.encoder = encoder;
        this.tx = tx;
        this.mailer = mailer;
        this.clock = clock;
    }

    /** A fresh account's first code: stored, then emailed. A mail failure is logged and never fails the signup. */
    public void start(User user) {
        String code = newCode();
        codes.saveAndFlush(EmailVerification.first(user.getId(), encoder.encode(code), clock.instant()));
        mailer.trySend(user.getEmail(), user.getDisplayName(), code);
    }

    private sealed interface Checked {
        record Verified(UserView user) implements Checked {
        }

        record Wrong(int attemptsLeft) implements Checked {
        }

        record Locked() implements Checked {
        }

        record Expired() implements Checked {
        }

        record AlreadyVerified() implements Checked {
        }
    }

    /**
     * Checks {@code code} for the account. Right: the account is verified, the code is gone, and the user's view
     * (now {@code emailVerified}) is returned. Otherwise an {@link AccountProblem}: wrong (with attempts left), locked
     * (after the 5th wrong code; then even the right one fails), expired or used, or already verified.
     */
    public UserView verify(long userId, String code) {
        // The wrong-code count must be committed, so the transaction returns an outcome instead of throwing.
        Checked outcome = tx.execute(status -> {
            User user = users.findById(userId).orElseThrow(UserNotFoundException::new);
            if (user.isEmailVerified()) {
                return new Checked.AlreadyVerified();
            }
            Optional<EmailVerification> found = codes.lockByUserId(userId);
            if (found.isEmpty()) {
                return new Checked.Expired();
            }
            EmailVerification pending = found.get();
            if (pending.locked()) {
                return new Checked.Locked();
            }
            if (pending.expired(clock.instant())) {
                return new Checked.Expired();
            }
            if (!encoder.matches(code, pending.codeHash())) {
                int left = pending.recordWrongCode();
                return left == 0 ? new Checked.Locked() : new Checked.Wrong(left);
            }
            codes.delete(pending);
            user.setEmailVerified(true);
            return new Checked.Verified(UserViews.of(user));
        });
        return switch (outcome) {
            case Checked.Verified v -> v.user();
            case Checked.Wrong w -> throw AccountProblem.codeInvalid(w.attemptsLeft());
            case Checked.Locked l -> throw AccountProblem.codeLocked();
            case Checked.Expired e -> throw AccountProblem.codeExpired();
            case Checked.AlreadyVerified a -> throw AccountProblem.alreadyVerified();
            case null -> throw new IllegalStateException("No verification outcome");
        };
    }

    private sealed interface Resend {
        record Send(String email, String displayName, String code) implements Resend {
        }

        record Wait(Duration delay) implements Resend {
        }

        record AlreadyVerified() implements Resend {
        }
    }

    /**
     * Replaces the account's code with a new one and emails it: 429 with {@code Retry-After} within 60 s of the last
     * email or after 5 emails in the hour, 409 when already verified, 503 when the mail server refuses it.
     */
    public void resend(long userId) {
        Resend outcome;
        try {
            outcome = tx.execute(status -> {
                User user = users.findById(userId).orElseThrow(UserNotFoundException::new);
                if (user.isEmailVerified()) {
                    return new Resend.AlreadyVerified();
                }
                Instant now = clock.instant();
                Optional<EmailVerification> found = codes.lockByUserId(userId);
                if (found.isPresent()) {
                    Optional<Duration> wait = found.get().resendWait(now);
                    if (wait.isPresent()) {
                        return new Resend.Wait(wait.get());
                    }
                }
                String code = newCode();
                String hash = encoder.encode(code);
                if (found.isPresent()) {
                    found.get().reissue(hash, now);
                } else {
                    codes.saveAndFlush(EmailVerification.first(userId, hash, now));
                }
                return new Resend.Send(user.getEmail(), user.getDisplayName(), code);
            });
        } catch (DataIntegrityViolationException raced) {
            // two first-ever resends at once (an account from before verification existed): one of them won
            outcome = new Resend.Wait(EmailVerification.RESEND_GAP);
        }
        switch (outcome) {
            case Resend.Send send -> {
                try {
                    mailer.send(send.email(), send.displayName(), send.code());
                } catch (MailException | IllegalArgumentException e) {
                    throw AccountProblem.mailUnavailable();
                }
            }
            case Resend.Wait w -> throw AccountProblem.resendThrottled(w.delay());
            case Resend.AlreadyVerified a -> throw AccountProblem.alreadyVerified();
            case null -> throw new IllegalStateException("No resend outcome");
        }
    }

    /** Six random digits, leading zeros included. */
    static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
