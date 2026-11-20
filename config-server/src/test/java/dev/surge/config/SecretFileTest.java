package dev.surge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * S10: compose mounts the password as a file, /run/secrets/env/CONFIG_PASSWORD (SURGE_SECRETS_DIR names another
 * directory), never as an environment variable. application.yml imports that directory as a config tree, so
 * {@code ${CONFIG_PASSWORD}} resolves from the file.
 */
class SecretFileTest {

    @TempDir
    Path secrets;

    @Test
    void thePasswordComesFromTheSecretFile() throws IOException {
        Files.writeString(secrets.resolve("CONFIG_PASSWORD"), "config-password-from-a-file\n");
        SpringApplication application = new SpringApplication(ConfigServerApplication.class);
        application.setDefaultProperties(Map.of("SURGE_SECRETS_DIR", secrets.toString(), "SURGE_ENV", "prod",
                "CONFIG_USER", "config", "server.port", "0", "CONFIG_REPO", secrets.toUri().toString()));

        try (ConfigurableApplicationContext context = application.run()) {
            String stored = context.getBean(UserDetailsService.class).loadUserByUsername("config").getPassword();
            assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                    .matches("config-password-from-a-file", stored)).isTrue();
        }
    }
}
