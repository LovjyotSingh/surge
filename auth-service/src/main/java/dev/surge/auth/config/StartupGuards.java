package dev.surge.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-fast checks that run while the context is built, so a misconfigured production deployment never starts.
 * {@code surge.env} (env SURGE_ENV) must be {@code dev} (local default) or {@code prod}. In prod the admin
 * password must be set explicitly, must not be the public local default, and must be at least 16 characters;
 * test-token minting must be off. Messages never contain the password.
 */
@Component
public class StartupGuards {
    static final String DEV_PASSWORD = "surge-admin";
    static final int MIN_PROD_PASSWORD_LENGTH = 16;

    private final String adminPassword;

    StartupGuards(@Value("${surge.env}") String env,
                  @Value("${surge.auth.admin-password:}") String configuredPassword,
                  @Value("${surge.auth.test-tokens.enabled:false}") boolean testTokens) {
        boolean dev = "dev".equals(env);
        if (!dev && !"prod".equals(env)) {
            throw new IllegalStateException("SURGE_ENV must be 'dev' or 'prod'");
        }
        if (!dev) {
            if (configuredPassword.length() < MIN_PROD_PASSWORD_LENGTH || DEV_PASSWORD.equals(configuredPassword)) {
                throw new IllegalStateException("SURGE_ADMIN_PASSWORD must be set to a non-default value of at "
                        + "least 16 characters when SURGE_ENV=prod");
            }
            if (testTokens) {
                throw new IllegalStateException("surge.auth.test-tokens.enabled must be false when SURGE_ENV=prod");
            }
        }
        // The local default exists only in dev; there is no fallback anywhere else.
        this.adminPassword = configuredPassword.isEmpty() ? DEV_PASSWORD : configuredPassword;
    }

    /** The password the admin account must have: the configured one, or the local default in dev only. */
    public String adminPassword() {
        return adminPassword;
    }
}
