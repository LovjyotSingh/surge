package dev.surge.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.surge.auth.error.AccountProblem;
import dev.surge.auth.error.BadCredentialsException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import dev.surge.auth.verify.EmailVerificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Admin sign-in logging without a Boot context (locking the shared context's admin would break other tests). */
@ExtendWith(OutputCaptureExtension.class)
class AuthServiceAdminLogTest {

    private static final String ADMIN = "admin@surge.local";
    private static final String LOCKED = "Sign-in for the admin account is locked";
    private static final String FAILED = "Failed sign-in for the admin account";

    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-17T10:00:00Z");

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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthService auth = new AuthService(users, encoder, mock(TokenService.class), clock,
            LoginThrottle.forTests(clock), new PasswordPolicy(), mock(EmailVerificationService.class), mock(TransactionTemplate.class), ADMIN);

    private void wrong() {
        auth.login(new LoginRequest(ADMIN, "not-the-password"));
    }

    private static long count(CapturedOutput output, String text) {
        return output.getOut().lines().filter(l -> l.contains(text)).count();
    }

    @Test
    void aLockedAdminIsReportedOncePerLockNotOncePerAttempt(CapturedOutput output) {
        User admin = new User(ADMIN, encoder.encode("the-admin-password"), "Surge Admin", Role.ADMIN, Instant.now());
        when(users.findByEmail(ADMIN)).thenReturn(Optional.of(admin));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(this::wrong).isInstanceOf(BadCredentialsException.class);
        }
        assertThat(count(output, FAILED)).isEqualTo(5);

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(this::wrong).isInstanceOf(AccountProblem.class);
        }
        assertThat(count(output, LOCKED)).isEqualTo(1);

        // the lock ends, one more failure starts a new (longer) lock: reported once again
        clock.advance(Duration.ofSeconds(30));
        assertThatThrownBy(this::wrong).isInstanceOf(BadCredentialsException.class);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(this::wrong).isInstanceOf(AccountProblem.class);
        }
        assertThat(count(output, LOCKED)).isEqualTo(2);
        assertThat(output.getOut()).doesNotContain(ADMIN).doesNotContain("not-the-password");
    }
}
