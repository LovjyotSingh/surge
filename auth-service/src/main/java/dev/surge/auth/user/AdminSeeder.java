package dev.surge.auth.user;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the local admin account on first start; the password comes from configuration, never from SQL. */
@Component
public class AdminSeeder implements ApplicationRunner {
    public static final String ADMIN_EMAIL = "admin@surge.local";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String adminPassword;

    AdminSeeder(UserRepository users, PasswordEncoder encoder, Clock clock,
                @Value("${surge.auth.admin-password}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(ADMIN_EMAIL)) {
            return;
        }
        try {
            users.save(new User(ADMIN_EMAIL, encoder.encode(adminPassword), "Surge Admin", Role.ADMIN,
                    clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // another instance seeded it first
        }
    }
}
