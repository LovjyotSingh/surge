package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * S10: passwords reach the services as files (compose secrets at /run/secrets/env/VARIABLE), never as environment
 * variables, and resolve exactly where the variable would have.
 */
class SecretFilesTest {

    @TempDir
    Path dir;

    private StandardEnvironment environment(Map<String, Object> variables) {
        Map<String, Object> env = new HashMap<>(variables);
        env.put(SecretFiles.DIRECTORY_VARIABLE, dir.toString());
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        // what application.yml says, ranked below the environment like config data
        environment.getPropertySources().addLast(new MapPropertySource("application.yml", Map.of(
                "spring.datasource.password", "${MYSQL_PASSWORD:surge}",
                "eureka.client.service-url.defaultZone", "http://surge:${EUREKA_PASSWORD:dev}@discovery:8761/eureka")));
        return environment;
    }

    private StandardEnvironment processed(Map<String, Object> variables) {
        StandardEnvironment environment = environment(variables);
        new SecretFiles().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void eachFileIsTheVariableOfItsName() throws IOException {
        write("MYSQL_PASSWORD", "0123456789abcdef0123");
        write("EUREKA_PASSWORD", "fedcba9876543210fedc");

        StandardEnvironment environment = processed(Map.of());

        assertThat(environment.getProperty("MYSQL_PASSWORD")).isEqualTo("0123456789abcdef0123");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("0123456789abcdef0123");
        assertThat(environment.getProperty("eureka.client.service-url.defaultZone"))
                .isEqualTo("http://surge:fedcba9876543210fedc@discovery:8761/eureka");
    }

    @Test
    void oneTrailingNewlineIsDropped() throws IOException {
        write("A_PASSWORD", "unix-line\n");
        write("B_PASSWORD", "windows-line\r\n");
        write("C_PASSWORD", "two-lines\n\n");
        write("D_PASSWORD", "  spaces kept  ");

        StandardEnvironment environment = processed(Map.of());

        assertThat(environment.getProperty("A_PASSWORD")).isEqualTo("unix-line");
        assertThat(environment.getProperty("B_PASSWORD")).isEqualTo("windows-line");
        assertThat(environment.getProperty("C_PASSWORD")).isEqualTo("two-lines\n");
        assertThat(environment.getProperty("D_PASSWORD")).isEqualTo("  spaces kept  ");
    }

    @Test
    void aVariableSetBothWaysIsRefusedWithoutShowingEitherValue() throws IOException {
        write("MYSQL_PASSWORD", "from-the-file-value");

        assertThatThrownBy(() -> processed(Map.of("MYSQL_PASSWORD", "from-the-environment")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_PASSWORD")
                .hasMessageNotContaining("from-the-file-value")
                .hasMessageNotContaining("from-the-environment");
    }

    @Test
    void anEmptyVariableDoesNotConflict() throws IOException {
        write("MAIL_PASSWORD", "provider-password");

        assertThat(processed(Map.of("MAIL_PASSWORD", "")).getProperty("MAIL_PASSWORD"))
                .isEqualTo("provider-password");
    }

    @Test
    void anEmptyFileIsRefused() throws IOException {
        write("S3_SECRET_KEY", "");
        write("QR_KEY", "\n");

        assertThatThrownBy(() -> processed(Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    @Test
    void aFileNameThatIsNotAVariableNameIsRefused() throws IOException {
        write("mysql-password", "0123456789abcdef");

        assertThatThrownBy(() -> processed(Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql-password");
    }

    @Test
    void hiddenFilesAndDirectoriesAreIgnored() throws IOException {
        write(".hidden", "x");
        Files.createDirectory(dir.resolve("..data"));
        Files.createDirectory(dir.resolve("NESTED"));
        write("REDIS_PASSWORD", "0123456789abcdef");

        StandardEnvironment environment = processed(Map.of());

        assertThat(environment.getProperty("REDIS_PASSWORD")).isEqualTo("0123456789abcdef");
        assertThat(environment.getProperty(".hidden")).isNull();
        assertThat(environment.getProperty("NESTED")).isNull();
    }

    @Test
    void withoutTheDirectoryNothingChanges() {
        Map<String, Object> env = new HashMap<>();
        env.put(SecretFiles.DIRECTORY_VARIABLE, dir.resolve("missing").toString());
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        int before = environment.getPropertySources().size();

        new SecretFiles().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().size()).isEqualTo(before);
        assertThat(environment.getPropertySources().contains(SecretFiles.SOURCE)).isFalse();
    }

    @Test
    void theDefaultDirectoryIsWhereComposeMountsTheSecrets() {
        assertThat(SecretFiles.DEFAULT_DIRECTORY).isEqualTo("/run/secrets/env");
    }

    /**
     * The config server's credentials ({@code spring.cloud.config.password: ${CONFIG_PASSWORD}}) are resolved while
     * the config data imports are processed, so the files must already be properties by then.
     */
    @Test
    void theFilesAreVisibleWhileConfigDataIsImported() throws IOException {
        assertThat(new SecretFiles().getOrder()).isLessThan(ConfigDataEnvironmentPostProcessor.ORDER);

        Path extra = Files.writeString(dir.resolveSibling(dir.getFileName() + "-extra.properties"),
                "surge.test.imported=yes\n");
        write("SURGE_TEST_EXTRA_CONFIG", extra.toUri().toString());
        SpringApplication application = new SpringApplication(Empty.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                SecretFiles.DIRECTORY_VARIABLE, dir.toString(),
                "spring.config.import", "${SURGE_TEST_EXTRA_CONFIG}"));
        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getProperty("surge.test.imported")).isEqualTo("yes");
        } finally {
            Files.deleteIfExists(extra);
        }
    }

    @Test
    void itIsRegisteredForEveryApplicationWithCommon() throws IOException {
        boolean registered = false;
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            try (InputStream in = url.openStream()) {
                String factories = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                registered |= factories.contains("org.springframework.boot.EnvironmentPostProcessor=")
                        && factories.contains(SecretFiles.class.getName());
            }
        }
        assertThat(registered).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
