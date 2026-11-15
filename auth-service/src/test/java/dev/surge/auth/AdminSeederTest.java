package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.surge.auth.user.AdminSeeder;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Seeder rotation with a real BCrypt encoder and a mocked repository: no Boot context. */
class AdminSeederTest {
    private static final String EMAIL = "ops@example.test";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);

    private AdminSeeder seeder(String password) {
        return new AdminSeeder(users, encoder, Clock.systemUTC(), EMAIL, password);
    }

    @Test
    void createsTheAdminWithTheConfiguredEmailAndPassword() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        seeder("first-password").run(null);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches("first-password", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void changedPasswordIsRehashedSoOnlyTheNewOneWorks() {
        User existing = new User(EMAIL, encoder.encode("old-password"), "Surge Admin", Role.ADMIN, Instant.now());
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        seeder("new-password").run(null);
        verify(users).save(existing);
        assertThat(encoder.matches("new-password", existing.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-password", existing.getPasswordHash())).isFalse();
    }

    @Test
    void unchangedPasswordIsLeftAlone() {
        User existing = new User(EMAIL, encoder.encode("same-password"), "Surge Admin", Role.ADMIN, Instant.now());
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        seeder("same-password").run(null);
        verify(users, never()).save(any());
    }
}
