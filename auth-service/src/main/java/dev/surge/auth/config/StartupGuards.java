package dev.surge.auth.config;

import dev.surge.auth.user.Emails;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-fast checks that run while the context is built, so a misconfigured deployment never starts.
 * {@code surge.env} (env SURGE_ENV) must be exactly {@code dev} (local default) or {@code prod}. In prod the admin
 * password must be set explicitly, must not be the public local default, and must have at least 16 characters;
 * test-token minting must be off. {@code surge.security.public-deployment=true} (set by the prod overlay as a
 * belt-and-braces check) refuses to start with {@code dev}, and with an admin email in the public {@code @surge.local}
 * domain (compose's default): anyone could sign up as that address first and lock the admin out. A local prod-mode
 * stack, which does not set the public flag, keeps working with it. Messages never contain the password or an email.
 */
@Component
public class StartupGuards {
    private static final Logger log = LoggerFactory.getLogger(StartupGuards.class);

    static final String DEV_PASSWORD = "surge-admin";
    static final int MIN_PROD_PASSWORD_LENGTH = 16;
    /** The domain of compose's default admin email, which anyone can guess. */
    static final String DEFAULT_EMAIL_DOMAIN = "@surge.local";
    /** BCrypt cannot hash more than 72 bytes. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final String adminPassword;
    private final String env;

    StartupGuards(@Value("${surge.env}") String env,
                  @Value("${surge.auth.admin-password:}") String configuredPassword,
                  @Value("${surge.auth.test-tokens.enabled:false}") boolean testTokens,
                  @Value("${surge.auth.admin-email}") String adminEmail,
                  @Value("${surge.security.public-deployment:false}") boolean publicDeployment) {
        boolean dev = "dev".equals(env);
        if (!dev && !"prod".equals(env)) {
            throw new IllegalStateException("SURGE_ENV must be exactly 'dev' or 'prod'");
        }
        if (dev && publicDeployment) {
            throw new IllegalStateException("SURGE_ENV=dev is not allowed when surge.security.public-deployment=true; "
                    + "set SURGE_ENV=prod");
        }
        if (adminEmail == null || adminEmail.isBlank() || !Emails.looksLikeEmail(Emails.normalize(adminEmail))) {
            throw new IllegalStateException("SURGE_ADMIN_EMAIL must be a valid email address");
        }
        if (!dev && publicDeployment && Emails.normalize(adminEmail).endsWith(DEFAULT_EMAIL_DOMAIN)) {
            throw new IllegalStateException("SURGE_ADMIN_EMAIL must not be an @surge.local address when "
                    + "SURGE_ENV=prod and SURGE_PUBLIC_DEPLOYMENT=true: that is the public default");
        }
        if (configuredPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalStateException("SURGE_ADMIN_PASSWORD must be at most 72 bytes in UTF-8");
        }
        if (!dev) {
            if (configuredPassword.isBlank()
                    || configuredPassword.codePointCount(0, configuredPassword.length()) < MIN_PROD_PASSWORD_LENGTH
                    || DEV_PASSWORD.equals(configuredPassword)) {
                throw new IllegalStateException("SURGE_ADMIN_PASSWORD must be set to a non-default value of at "
                        + "least 16 characters when SURGE_ENV=prod");
            }
            if (testTokens) {
                throw new IllegalStateException("surge.auth.test-tokens.enabled must be false when SURGE_ENV=prod");
            }
        }
        this.env = env;
        // The local default exists only in dev; there is no fallback anywhere else.
        this.adminPassword = configuredPassword.isEmpty() ? DEV_PASSWORD : configuredPassword;
        if (DEV_PASSWORD.equals(this.adminPassword)) {
            log.warn("Using the public development admin password - never expose this instance");
        }
    }

    /** The password the admin account must have: the configured one, or the local default in dev only. */
    public String adminPassword() {
        return adminPassword;
    }

    public String env() {
        return env;
    }
}
