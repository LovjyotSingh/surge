package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** SEC-27: springdoc's API docs and Swagger UI exist only in SURGE_ENV=dev. */
class DevOnlyApiDocsTest {

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test", properties));
        new DevOnlyApiDocs().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void devLeavesTheDocsAlone() {
        for (Map<String, Object> dev : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("surge.env", "dev"))) {
            StandardEnvironment environment = environment(dev);
            assertThat(environment.getProperty("springdoc.api-docs.enabled")).isNull();
            assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isNull();
        }
    }

    @Test
    void anythingButDevTurnsThemOffAndNothingCanTurnThemBackOn() {
        for (Map<String, Object> notDev : java.util.List.<Map<String, Object>>of(
                Map.of("surge.env", "prod"),
                Map.of("surge.env", "prod", "springdoc.api-docs.enabled", "true", "springdoc.swagger-ui.enabled",
                        "true"),
                // refused at startup anyway (SurgeEnv); the docs must not be on while it fails
                Map.of("surge.env", "Prod"),
                Map.of("surge.env", "dev", "surge.security.public-deployment", "true"))) {
            StandardEnvironment environment = environment(notDev);
            assertThat(environment.getProperty("springdoc.api-docs.enabled")).as(notDev.toString()).isEqualTo("false");
            assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).as(notDev.toString())
                    .isEqualTo("false");
        }
    }

    @Test
    void itIsRegisteredForEveryApplicationWithCommon() throws IOException {
        boolean registered = false;
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            try (InputStream in = url.openStream()) {
                String factories = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                registered |= factories.contains("org.springframework.boot.EnvironmentPostProcessor=")
                        && factories.contains(DevOnlyApiDocs.class.getName());
            }
        }
        assertThat(registered).isTrue();
    }
}
