package dev.surge.common.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Passwords as files, not environment variables (S10). Compose mounts each secret a service needs at
 * {@value #DEFAULT_DIRECTORY}/{@code <VARIABLE>} (env {@value #DIRECTORY_VARIABLE} names another directory), and each
 * file there becomes the property {@code <VARIABLE>} with the file's content, one trailing newline removed. So
 * application.yml's {@code ${MYSQL_PASSWORD:…}}, {@code ${CONFIG_PASSWORD:…}}, {@code ${EUREKA_PASSWORD:…}} and the
 * rest resolve exactly as if the variable were set, while {@code docker inspect}, {@code /proc/1/environ} and any child
 * process show nothing.
 *
 * <p>It runs before config data ({@link #getOrder}): the config-server import resolves its credentials while config
 * data is processed, so a later source would leave it with the development password (and, being optional, without
 * its remote configuration). The property source ranks right above the real environment (a variable set to an empty
 * value cannot hide its file) and so above every config file.
 *
 * <p>Refused at startup, naming the variable and never a value: a variable set both ways (non-empty in the
 * environment and as a file), an empty file, a file name that is not a variable name. Hidden entries (Kubernetes'
 * {@code ..data}) and directories are skipped. No directory, nothing happens (local runs and tests).
 */
public class SecretFiles implements EnvironmentPostProcessor, Ordered {

    public static final String DIRECTORY_VARIABLE = "SURGE_SECRETS_DIR";
    static final String DEFAULT_DIRECTORY = "/run/secrets/env";
    static final String SOURCE = "surgeSecretFiles";

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path directory = Path.of(environment.getProperty(DIRECTORY_VARIABLE, DEFAULT_DIRECTORY));
        if (!Files.isDirectory(directory)) {
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> variables = sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Map<String, Object> values = read(directory, variables);
        if (values.isEmpty()) {
            return;
        }
        MapPropertySource secrets = new MapPropertySource(SOURCE, values);
        if (variables != null) {
            // above the environment, so an empty variable (compose's "${MAIL_PASSWORD:-}") cannot hide the file
            sources.addBefore(variables.getName(), secrets);
        } else {
            sources.addFirst(secrets);
        }
    }

    /** Before {@link ConfigDataEnvironmentPostProcessor}: see the class comment. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    private static Map<String, Object> read(Path directory, PropertySource<?> variables) {
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.filter(file -> !file.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the secret files in " + directory, e);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalStateException("Secret file " + file + " must be named after the variable it sets "
                        + "(letters, digits and _ only), e.g. MYSQL_PASSWORD");
            }
            Object variable = variables == null ? null : variables.getProperty(name);
            if (variable != null && !variable.toString().isEmpty()) {
                throw new IllegalStateException(name + " is set both as an environment variable and as the secret file "
                        + file + "; set it one way only");
            }
            String value = withoutTrailingNewline(readString(file));
            if (value.isEmpty()) {
                throw new IllegalStateException("Secret file " + file + " for " + name + " is empty");
            }
            values.put(name, value);
        }
        return values;
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the secret file " + file, e);
        }
    }

    private static String withoutTrailingNewline(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
