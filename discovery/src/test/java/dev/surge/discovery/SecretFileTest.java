package dev.surge.discovery;

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
 * S10: compose mounts the password as a file, /run/secrets/env/EUREKA_PASSWORD (SURGE_SECRETS_DIR names another
 * directory), never as an environment variable. application.yml imports that directory as a config tree, so
 * {@code ${EUREKA_PASSWORD}} resolves from the file.
 */
class SecretFileTest {

    @TempDir
    Path secrets;

    @Test
    void thePasswordComesFromTheSecretFile() throws IOException {
        Files.writeString(secrets.resolve("EUREKA_PASSWORD"), "eureka-password-from-a-file\n");
        SpringApplication application = new SpringApplication(DiscoveryApplication.class);
        application.setDefaultProperties(Map.of("SURGE_SECRETS_DIR", secrets.toString(), "SURGE_ENV", "prod",
                "EUREKA_USER", "surge", "server.port", "0"));

        try (ConfigurableApplicationContext context = application.run()) {
            String stored = context.getBean(UserDetailsService.class).loadUserByUsername("surge").getPassword();
            assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                    .matches("eureka-password-from-a-file", stored)).isTrue();
        }
    }
}
