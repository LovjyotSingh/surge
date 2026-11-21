package dev.surge.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The one HTTP Basic user this server accepts. discovery does not depend on {@code common}, so the
 * {@code SURGE_ENV} rules are repeated here (config-server has the same class): {@code surge.env} must be exactly
 * {@code dev} or {@code prod}, and {@code dev} is refused when {@code surge.security.public-deployment=true}.
 *
 * <ul>
 *   <li>Both values set: used as they are, except that prod refuses the development password and a password shorter
 *       than 16 characters. Only letters, digits and {@code . _ ~ -} are accepted, in any environment.</li>
 *   <li>Neither set: the development credentials with a warning in dev (every service's application.yml sends the
 *       same ones by default); prod refuses to start.</li>
 *   <li>Only one set: refused everywhere, as a likely typo.</li>
 * </ul>
 *
 * The password is never logged; {@link #toString()} hides it.
 */
record BasicCredentials(String username, String password) {

    private static final Logger log = LoggerFactory.getLogger(BasicCredentials.class);
    /** Unreserved URL characters: Eureka clients send the pair in their defaultZone URL, with no percent-encoding. */
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final int MIN_PROD_PASSWORD = 16;

    /**
     * @param prefix      property prefix holding {@code username} and {@code password}
     * @param userVar     the environment variable named in messages for the user
     * @param passwordVar the environment variable named in messages for the password
     */
    static BasicCredentials resolve(Environment environment, String prefix, String userVar, String passwordVar,
            BasicCredentials development) {
        boolean prod = switch (environment.getProperty("surge.env", "dev")) {
            case "dev" -> false;
            case "prod" -> true;
            default -> throw new IllegalStateException("SURGE_ENV must be exactly 'dev' or 'prod'");
        };
        if (!prod && environment.getProperty("surge.security.public-deployment", Boolean.class, false)) {
            throw new IllegalStateException("SURGE_ENV=dev is not allowed when surge.security.public-deployment=true; "
                    + "set SURGE_ENV=prod");
        }
        refuseBothWays(environment, passwordVar);
        String username = environment.getProperty(prefix + ".username", "").strip();
        String password = environment.getProperty(prefix + ".password", "");
        boolean noUser = username.isEmpty();
        boolean noPassword = password.isBlank();
        if (noUser != noPassword) {
            throw new IllegalStateException("Set both " + userVar + " and " + passwordVar
                    + ", or neither (SURGE_ENV=dev only)");
        }
        if (noUser) {
            if (prod) {
                throw new IllegalStateException(userVar + " and " + passwordVar + " must be set when SURGE_ENV=prod");
            }
            log.warn("{} and {} are not set: accepting the development credentials (user {}). Only SURGE_ENV=dev "
                    + "allows this.", userVar, passwordVar, development.username());
            return development;
        }
        if (!URL_SAFE.matcher(username).matches() || !URL_SAFE.matcher(password).matches()) {
            throw new IllegalStateException(userVar + " and " + passwordVar
                    + " may contain only letters, digits and . _ ~ -");
        }
        if (prod && password.equals(development.password())) {
            throw new IllegalStateException(passwordVar + " must not be the development password when SURGE_ENV=prod");
        }
        if (prod && password.length() < MIN_PROD_PASSWORD) {
            throw new IllegalStateException(passwordVar + " must be at least " + MIN_PROD_PASSWORD
                    + " characters when SURGE_ENV=prod");
        }
        return new BasicCredentials(username, password);
    }

    /**
     * S10: compose mounts the password as the file {@code <SURGE_SECRETS_DIR or /run/secrets/env>/<passwordVar>}, which
     * application.yml imports as a config tree. There a non-empty environment variable of the same name would silently
     * win, so, as the services' SecretFiles does, a password set both ways is refused (naming the variable only).
     */
    private static void refuseBothWays(Environment environment, String passwordVar) {
        Path file = Path.of(environment.getProperty("SURGE_SECRETS_DIR", "/run/secrets/env"), passwordVar);
        if (!Files.isRegularFile(file) || !(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        PropertySource<?> variables = configurable.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Object variable = variables == null ? null : variables.getProperty(passwordVar);
        if (variable != null && !variable.toString().isEmpty()) {
            throw new IllegalStateException(passwordVar + " is set both as an environment variable and as the secret "
                    + "file " + file + "; set it one way only");
        }
    }

    @Override
    public String toString() {
        return "BasicCredentials[username=" + username + ", password=****]";
    }
}
