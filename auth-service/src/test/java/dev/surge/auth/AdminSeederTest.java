package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Seeder behaviour with a real BCrypt encoder and a mocked repository: no Boot context. */
class AdminSeederTest {
    private static final String EMAIL = "ops@example.test";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);

    private AdminSeeder seeder(String password, String env) {
        return new AdminSeeder(users, encoder, Clock.systemUTC(), EMAIL, password, env);
    }

    private User admin(String email, String password) {
        return new User(email, encoder.encode(password), "Surge Admin", Role.ADMIN, Instant.now());
    }

    @Test
    void createsTheAdminWithTheConfiguredEmailAndPassword() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        seeder("first-password", "dev").run(null);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(encoder.matches("first-password", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void changedPasswordIsRehashedSoOnlyTheNewOneWorks() {
        User existing = admin(EMAIL, "old-password");
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        seeder("new-password", "dev").run(null);
        verify(users).save(existing);
        assertThat(encoder.matches("new-password", existing.getPasswordHash())).isTrue();
        assertThat(encoder.matches("old-password", existing.getPasswordHash())).isFalse();
    }

    @Test
    void unchangedPasswordIsLeftAlone() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(admin(EMAIL, "same-password")));
        seeder("same-password", "dev").run(null);
        verify(users, never()).save(any());
    }

    @Test
    void anExistingNonAdminAtTheConfiguredEmailIsNeverTakenOver() {
        User customer = new User(EMAIL, encoder.encode("customer-pw"), "Customer", Role.USER, Instant.now());
        String hash = customer.getPasswordHash();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(customer));
        for (String env : new String[] {"dev", "prod"}) {
            assertThatThrownBy(() -> seeder("new-password", env).run(null)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not an administrator");
        }
        assertThat(customer.getPasswordHash()).isEqualTo(hash);
        assertThat(customer.getRole()).isEqualTo(Role.USER);
        verify(users, never()).save(any());
    }

    @Test
    void leftoverAdminsFailStartupInProdWithoutNamingThem() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(admin(EMAIL, "pw-pw-pw-pw-pw-pw")));
        when(users.findByRoleAndEmailNot(Role.ADMIN, EMAIL))
                .thenReturn(List.of(admin("admin@surge.local", "surge-admin"), admin("old@example.test", "x")));
        assertThatThrownBy(() -> seeder("pw-pw-pw-pw-pw-pw", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("2 administrator account(s)")
                .hasMessageNotContaining("surge.local").hasMessageNotContaining("example.test");
    }

    @Test
    void leftoverAdminsAreDemotedAndScrambledInDev() {
        User old = admin("admin@surge.local", "surge-admin");
        String oldHash = old.getPasswordHash();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(admin(EMAIL, "new-password")));
        when(users.findByRoleAndEmailNot(Role.ADMIN, EMAIL)).thenReturn(List.of(old));
        seeder("new-password", "dev").run(null);
        verify(users).save(old);
        assertThat(old.getRole()).isEqualTo(Role.USER);
        assertThat(old.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(encoder.matches("surge-admin", old.getPasswordHash())).isFalse();
    }
}
