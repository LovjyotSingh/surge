package dev.surge.common.security;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * SEC-27: springdoc's API docs and Swagger UI exist only in {@code SURGE_ENV=dev}. Anywhere else (prod, an invalid
 * value that startup refuses anyway, or dev on a public deployment) this adds
 * {@code springdoc.api-docs.enabled=false} and {@code springdoc.swagger-ui.enabled=false} as the
 * highest-precedence property source, so no environment variable or config-server file can turn them back on.
 * Registered in {@code META-INF/spring.factories} for every application with {@code common}; it runs after the config
 * data (config server included) has been loaded. Without springdoc on the classpath the two properties do nothing.
 */
public class DevOnlyApiDocs implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "surgeDevOnlyApiDocs";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isDev(environment)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, Map.of(
                "springdoc.api-docs.enabled", "false",
                "springdoc.swagger-ui.enabled", "false")));
    }

    private static boolean isDev(ConfigurableEnvironment environment) {
        try {
            return SurgeEnv.from(environment) == SurgeEnv.DEV;
        } catch (RuntimeException e) {
            // an invalid SURGE_ENV or public-deployment flag (refused at startup by SurgeEnvironmentAutoConfiguration)
            // is never dev
            return false;
        }
    }

    /** After {@code ConfigDataEnvironmentPostProcessor}, so {@code surge.env} from every config source is seen. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
