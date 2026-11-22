package dev.surge.auth.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Signup's half of verification, without a database: a failure after the account exists never fails the signup. */
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationServiceTest {

    private final EmailVerificationRepository codes = mock(EmailVerificationRepository.class);
    private final VerificationMailer mailer = mock(VerificationMailer.class);
    private final EmailVerificationService service = new EmailVerificationService(mock(UserRepository.class), codes,
            new BCryptPasswordEncoder(4), mock(TransactionTemplate.class), mailer, mock(VerificationMailQuota.class),
            Clock.systemUTC());

    private static User user() {
        User user = new User("ada@example.com", "hash", "Ada", Role.USER, Instant.now());
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }

    @Test
    void aCodeThatCannotBeStoredIsLoggedNotThrownAndNothingIsEmailed(CapturedOutput output) {
        when(codes.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatCode(() -> service.start(user())).doesNotThrowAnyException();
        verify(mailer, never()).trySend(anyString(), anyString());
        assertThat(output.getOut()).contains("Could not store a verification code at signup")
                .doesNotContain("ada@example.com");
    }

    @Test
    void aStoredCodeIsEmailed() {
        service.start(user());
        verify(codes).saveAndFlush(any());
        verify(mailer).trySend(org.mockito.ArgumentMatchers.eq("ada@example.com"),
                org.mockito.ArgumentMatchers.matches("^[0-9]{6}$"));
    }
}
