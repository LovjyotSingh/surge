package dev.surge.auth.api;

import dev.surge.auth.error.AccountProblem;
import dev.surge.auth.error.BadCredentialsException;
import dev.surge.auth.error.EmailTakenException;
import dev.surge.auth.error.InvalidBodyException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.auth.user.Emails;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import dev.surge.auth.verify.EmailVerificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signup and sign-in. Signup applies the {@link PasswordPolicy}, creates an unverified account and emails its
 * verification code. Sign-in goes through the per-account {@link LoginThrottle} first: a locked account gets 429
 * without any password check, for registered and unknown emails alike. Failed or throttled sign-ins of the admin
 * account are logged at WARN, never with the email.
 *
 * <p>An address whose account is still unverified after {@link #UNVERIFIED_LIFETIME} is up for grabs: a new signup
 * deletes that account (its code goes with it) and creates its own, so the real owner of an address is never locked
 * out by someone else's abandoned signup. A verified account, the admin and an unverified account younger than that
 * are never replaced (409), and the delete itself only matches an unverified USER row, so a verification that lands
 * during the signup wins. Every signup email also counts against the address's daily budget.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** How long an unverified account keeps its email address to itself. */
    public static final Duration UNVERIFIED_LIFETIME = Duration.ofHours(24);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final Clock clock;
    private final LoginThrottle throttle;
    private final PasswordPolicy passwords;
    private final EmailVerificationService verification;
    private final TransactionTemplate tx;
    /** Checked against when the email is unknown, so both failure paths cost one BCrypt comparison. */
    private final String dummyHash;

    private final String adminEmail;
    /** Until when the current admin lock has been reported; guarded by {@code this}. */
    private Instant adminLockWarnedUntil;

    AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, Clock clock, LoginThrottle throttle,
                PasswordPolicy passwords, EmailVerificationService verification, TransactionTemplate tx,
                @Value("${surge.auth.admin-email}") String adminEmail) {
        this.adminEmail = Emails.normalize(adminEmail);
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.clock = clock;
        this.throttle = throttle;
        this.passwords = passwords;
        this.verification = verification;
        this.tx = tx;
        this.dummyHash = encoder.encode("surge-dummy-password");
    }

    public TokenResponse signup(SignupRequest req) {
        String email = Emails.normalize(req.email());
        Optional<String> weak = passwords.problem(email, req.password());
        if (weak.isPresent()) {
            throw new InvalidBodyException(Map.of("password", weak.get()));
        }
        Instant now = clock.instant();
        Instant staleBefore = now.minus(UNVERIFIED_LIFETIME);
        Optional<User> existing = users.findByEmail(email);
        if (adminEmail.equals(email)
                || existing.isPresent() && !stale(existing.get(), staleBefore)) {
            throw new EmailTakenException();
        }
        verification.reserveSend(email);
        User fresh = new User(email, encoder.encode(req.password()), req.displayName().trim(), Role.USER, now);
        User saved;
        try {
            saved = tx.execute(status -> {
                if (existing.isPresent() && users.deleteStaleUnverified(existing.get().getId(), staleBefore) == 0) {
                    throw new EmailTakenException();
                }
                return users.saveAndFlush(fresh);
            });
        } catch (DataIntegrityViolationException e) {
            throw new EmailTakenException();
        }
        verification.start(saved);
        return tokens.issue(UserViews.of(saved));
    }

    private static boolean stale(User user, Instant staleBefore) {
        return !user.isEmailVerified() && user.getRole() == Role.USER && user.getCreatedAt().isBefore(staleBefore);
    }

    public TokenResponse login(LoginRequest req) {
        String email = Emails.normalize(req.email());
        boolean admin = adminEmail.equals(email);
        Optional<Duration> locked = throttle.tryAcquire(email);
        if (locked.isPresent()) {
            if (admin) {
                warnAdminLockedOnce(locked.get());
            }
            throw AccountProblem.loginThrottled(locked.get());
        }
        Optional<User> found = users.findByEmail(email);
        boolean matches = encoder.matches(req.password(), found.map(User::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            if (admin) {
                log.warn("Failed sign-in for the admin account");
            }
            throw new BadCredentialsException();
        }
        throttle.succeeded(email);
        return tokens.issue(UserViews.of(found.get()));
    }

    /**
     * One WARN per lock of the admin account, not one per refused attempt: a botnet hammering a locked admin must not
     * flood the log. The next lock (after the next failure) warns again.
     */
    private void warnAdminLockedOnce(Duration wait) {
        Instant now = clock.instant();
        Instant lockEnds = now.plus(wait);
        synchronized (this) {
            if (adminLockWarnedUntil != null && now.isBefore(adminLockWarnedUntil)) {
                return;
            }
            adminLockWarnedUntil = lockEnds;
        }
        log.warn("Sign-in for the admin account is locked for {} s after repeated failures", wait.toSeconds());
    }
}
