package dev.surge.auth.api;

import dev.surge.auth.error.BadCredentialsException;
import dev.surge.auth.error.EmailTakenException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import java.time.Clock;
import dev.surge.auth.user.Emails;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final Clock clock;
    /** Checked against when the email is unknown, so both failure paths cost one BCrypt comparison. */
    private final String dummyHash;

    private final String adminEmail;

    AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, Clock clock,
                @org.springframework.beans.factory.annotation.Value("${surge.auth.admin-email}") String adminEmail) {
        this.adminEmail = Emails.normalize(adminEmail);
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = encoder.encode("surge-dummy-password");
    }

    public TokenResponse signup(SignupRequest req) {
        String email = Emails.normalize(req.email());
        if (adminEmail.equals(email) || users.existsByEmail(email)) {
            throw new EmailTakenException();
        }
        User saved;
        try {
            saved = users.saveAndFlush(new User(email, encoder.encode(req.password()), req.displayName().trim(),
                    Role.USER, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new EmailTakenException();
        }
        return tokens.issue(UserViews.of(saved));
    }

    public TokenResponse login(LoginRequest req) {
        Optional<User> found = users.findByEmail(Emails.normalize(req.email()));
        boolean matches = encoder.matches(req.password(), found.map(User::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            throw new BadCredentialsException();
        }
        return tokens.issue(UserViews.of(found.get()));
    }
}
