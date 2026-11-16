package dev.surge.auth.user;

import dev.surge.auth.config.StartupGuards;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the admin account on first start and re-hashes its password whenever the configured one no longer
 * matches the stored hash, so rotating SURGE_ADMIN_PASSWORD takes effect on the next start. The password comes
 * from configuration, never from SQL, and is never logged.
 *
 * <p>The configured email is the only admin. Any other ADMIN row (for example the dev account left behind after
 * SURGE_ADMIN_EMAIL changed) fails startup in prod and is demoted with a scrambled password in dev. An existing
 * non-admin account at the configured email is never touched: startup fails instead.
 *
 * <p>It runs as a {@link SmartInitializingSingleton}: once every singleton (Flyway and JPA included) exists, but before
 * the web server starts. A refusal therefore aborts startup before any port is open, so a leftover admin can never sign
 * in during a failing prod start (an {@code ApplicationRunner} would run only after the server was already serving).
 */
@Component
public class AdminSeeder implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean prod;

    @Autowired
    AdminSeeder(UserRepository users, PasswordEncoder encoder, Clock clock,
                @Value("${surge.auth.admin-email}") String adminEmail, StartupGuards guards) {
        this(users, encoder, clock, adminEmail, guards.adminPassword(), guards.env());
    }

    public AdminSeeder(UserRepository users, PasswordEncoder encoder, Clock clock, String adminEmail,
                       String adminPassword, String env) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.adminEmail = Emails.normalize(adminEmail);
        this.adminPassword = adminPassword;
        this.prod = "prod".equals(env);
    }

    @Override
    public void afterSingletonsInstantiated() {
        seed();
    }

    /** Creates, re-hashes or refuses as described above. */
    public void seed() {
        Optional<User> existing = users.findByEmail(adminEmail);
        if (existing.isPresent()) {
            User admin = existing.get();
            if (admin.getRole() != Role.ADMIN) {
                throw new IllegalStateException("SURGE_ADMIN_EMAIL names an existing account that is not an "
                        + "administrator; refusing to take it over. Choose another admin email");
            }
            if (!encoder.matches(adminPassword, admin.getPasswordHash())) {
                admin.setPasswordHash(encoder.encode(adminPassword));
                users.save(admin);
                log.info("Admin account password re-hashed to match the configured SURGE_ADMIN_PASSWORD");
            }
        } else {
            try {
                users.save(new User(adminEmail, encoder.encode(adminPassword), "Surge Admin", Role.ADMIN,
                        clock.instant()));
            } catch (DataIntegrityViolationException raced) {
                // another instance seeded it first
            }
        }
        handleStaleAdmins();
    }

    private void handleStaleAdmins() {
        List<User> stale = users.findByRoleAndEmailNot(Role.ADMIN, adminEmail);
        if (stale.isEmpty()) {
            return;
        }
        if (prod) {
            throw new IllegalStateException(stale.size() + " administrator account(s) exist besides the configured "
                    + "SURGE_ADMIN_EMAIL; demote or delete them before starting in prod");
        }
        for (User old : stale) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            old.setRole(Role.USER);
            old.setPasswordHash(encoder.encode(Base64.getEncoder().encodeToString(random)));
            users.save(old);
        }
        log.warn("Demoted {} leftover administrator account(s) to USER and scrambled their passwords "
                + "(SURGE_ADMIN_EMAIL changed)", stale.size());
    }
}
