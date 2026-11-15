package dev.surge.auth.user;

import dev.surge.auth.config.StartupGuards;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the admin account on first start and re-hashes its password whenever the configured one no longer
 * matches the stored hash, so rotating SURGE_ADMIN_PASSWORD takes effect on the next start. The password comes
 * from configuration, never from SQL, and is never logged.
 */
@Component
public class AdminSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String adminEmail;
    private final String adminPassword;

    @Autowired
    AdminSeeder(UserRepository users, PasswordEncoder encoder, Clock clock,
                @Value("${surge.auth.admin-email}") String adminEmail, StartupGuards guards) {
        this(users, encoder, clock, adminEmail, guards.adminPassword());
    }

    public AdminSeeder(UserRepository users, PasswordEncoder encoder, Clock clock, String adminEmail,
                       String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.adminEmail = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<User> existing = users.findByEmail(adminEmail);
        if (existing.isPresent()) {
            User admin = existing.get();
            if (!encoder.matches(adminPassword, admin.getPasswordHash())) {
                admin.setPasswordHash(encoder.encode(adminPassword));
                users.save(admin);
                log.info("Admin account password re-hashed to match the configured SURGE_ADMIN_PASSWORD");
            }
            return;
        }
        // BCrypt accepts at most 72 bytes: a longer SURGE_ADMIN_PASSWORD makes encode() throw and startup fail fast.
        try {
            users.save(new User(adminEmail, encoder.encode(adminPassword), "Surge Admin", Role.ADMIN,
                    clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // another instance seeded it first
        }
    }
}
