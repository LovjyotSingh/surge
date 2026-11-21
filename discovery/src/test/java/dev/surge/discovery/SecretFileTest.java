package dev.surge.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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

    /**
     * In a config tree a non-empty environment variable would silently win over the file; like the services'
     * SecretFiles, setting the password both ways is refused, naming the variable and no value.
     */
    @Test
    void aPasswordSetBothWaysIsRefused() throws IOException {
        Files.writeString(secrets.resolve("EUREKA_PASSWORD"), "eureka-password-from-a-file");
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(DiscoverySecurity.class)
                .withPropertyValues("SURGE_SECRETS_DIR=" + secrets, "surge.discovery.username=surge",
                        "surge.discovery.password=eureka-password-from-the-env")
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("EUREKA_PASSWORD", "eureka-password-from-the-env"))));

        runner.run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("EUREKA_PASSWORD").hasMessageContaining("both")
                .message().doesNotContain("from-the-env").doesNotContain("from-a-file"));

        // an empty variable, or no file, is no conflict
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("EUREKA_PASSWORD", ""))))
                .run(context -> assertThat(context).hasNotFailed());
        Files.delete(secrets.resolve("EUREKA_PASSWORD"));
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
