package dev.surge.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.env.Environment;

/**
 * Runs {@link StoreCredentials#check} at startup in every application with {@code common} on its classpath (every
 * service and the gateway): with {@code SURGE_ENV=prod}, missing or development credentials for a store the service
 * uses stop it before it connects anywhere.
 */
@AutoConfiguration
public class StoreCredentialsAutoConfiguration {

    StoreCredentialsAutoConfiguration(Environment environment) {
        StoreCredentials.check(environment);
    }
}
