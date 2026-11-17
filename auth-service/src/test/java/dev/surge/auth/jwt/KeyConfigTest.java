package dev.surge.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The key beans as auth builds them: prod refuses to start without SURGE_JWT_KEY_FILE (no Boot app, no database). */
class KeyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(KeyConfig.class);

    @TempDir
    Path dir;

    @Test
    void prodWithoutAKeyFileFailsToStart() {
        runner.withPropertyValues("surge.env=prod").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("SURGE_JWT_KEY_FILE")
                    .hasMessageContaining("SURGE_ENV=prod");
        });
        runner.withPropertyValues("surge.env=prod", "surge.auth.jwt.key-file=").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void devWithoutAKeyFileGeneratesOne() {
        runner.withPropertyValues("surge.env=dev").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RSAKey.class).isPrivate()).isTrue();
            assertThat(ctx.getBean(JWKSet.class).getKeys()).hasSize(1);
        });
    }

    @Test
    void prodWithAKeyFileAndAPreviousKeyServesBoth() throws Exception {
        RSAKey current = new RSAKeyGenerator(2048).keyID("current").generate();
        RSAKey previous = new RSAKeyGenerator(2048).keyID("previous").generate();
        Path currentFile = Files.writeString(dir.resolve("current.jwk"), current.toJSONString());
        Path previousFile = Files.writeString(dir.resolve("previous.jwk"), previous.toPublicJWK().toJSONString());
        runner.withPropertyValues("surge.env=prod", "surge.auth.jwt.key-file=" + currentFile,
                "surge.auth.jwt.previous-key-file=" + previousFile).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(RSAKey.class).getKeyID()).isEqualTo("current");
                    assertThat(ctx.getBean(JWKSet.class).getKeys()).extracting(k -> k.getKeyID())
                            .containsExactly("current", "previous");
                });
    }

    /** The compose switches are SURGE_JWT_KEY_FILE and SURGE_JWT_PREVIOUS_KEY_FILE; they must reach the properties. */
    @Test
    void theEnvironmentVariablesBindToTheProperties() {
        new ApplicationContextRunner().withInitializer(KeyConfigTest::loadApplicationYml)
                .withSystemProperties("SURGE_JWT_KEY_FILE=/run/secrets/jwt.jwk",
                        "SURGE_JWT_PREVIOUS_KEY_FILE=/run/secrets/jwt-previous.jwk")
                .run(ctx -> {
                    assertThat(ctx.getEnvironment().getProperty("surge.auth.jwt.key-file"))
                            .isEqualTo("/run/secrets/jwt.jwk");
                    assertThat(ctx.getEnvironment().getProperty("surge.auth.jwt.previous-key-file"))
                            .isEqualTo("/run/secrets/jwt-previous.jwk");
                });
        new ApplicationContextRunner().withInitializer(KeyConfigTest::loadApplicationYml)
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("surge.auth.jwt.key-file")).isEmpty());
    }

    private static void loadApplicationYml(org.springframework.context.ConfigurableApplicationContext ctx) {
        try {
            var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
            sources.forEach(s -> ctx.getEnvironment().getPropertySources().addLast(s));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
