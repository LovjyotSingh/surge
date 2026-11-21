package dev.surge.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.env.Environment;

/**
 * Runs {@link MailSettings#check} at startup in every application with {@code common} on its classpath: on a public
 * deployment, auth-service and notification-service refuse to start without SMTP AUTH, required STARTTLS and a real
 * From address.
 */
@AutoConfiguration
public class MailSettingsAutoConfiguration {

    MailSettingsAutoConfiguration(Environment environment) {
        MailSettings.check(environment);
    }
}
