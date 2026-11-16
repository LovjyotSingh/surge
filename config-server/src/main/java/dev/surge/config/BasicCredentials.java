package dev.surge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The one HTTP Basic user this server accepts. config-server does not depend on {@code common}, so the
 * {@code SURGE_ENV} rules are repeated here (discovery has the same class): {@code surge.env} must be exactly
 * {@code dev} or {@code prod}, and {@code dev} is refused when {@code surge.security.public-deployment=true}.
 *
 * <ul>
 *   <li>Both values set: used as they are, except that prod refuses the development password.</li>
 *   <li>Neither set: the development credentials with a warning in dev (every service's application.yml sends the
 *       same ones by default); prod refuses to start.</li>
 *   <li>Only one set: refused everywhere, as a likely typo.</li>
 * </ul>
 *
 * The password is never logged; {@link #toString()} hides it.
 */
record BasicCredentials(String username, String password) {

    private static final Logger log = LoggerFactory.getLogger(BasicCredentials.class);

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
        if (prod && password.equals(development.password())) {
            throw new IllegalStateException(passwordVar + " must not be the development password when SURGE_ENV=prod");
        }
        return new BasicCredentials(username, password);
    }

    @Override
    public String toString() {
        return "BasicCredentials[username=" + username + ", password=****]";
    }
}
