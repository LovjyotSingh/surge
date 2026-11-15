package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.auth.config.StartupGuards;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Guard logic only (no Boot context, no database): a failing guard must fail context startup. */
class StartupGuardsTest {
    private static final String STRONG = "a-long-random-admin-password";
    private static final String EMAIL = "surge.auth.admin-email=ops@example.test";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StartupGuards.class).withPropertyValues(EMAIL);

    private static String pw(String value) {
        return "surge.auth.admin-password=" + value;
    }

    @Test
    void prodWithTheDefaultPasswordFailsToStart() {
        runner.withPropertyValues("surge.env=prod", pw("surge-admin")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "SURGE_ADMIN_PASSWORD must be set to a non-default value of at least 16 characters when SURGE_ENV=prod");
        });
    }

    @Test
    void prodPasswordLengthBoundaries() {
        runner.withPropertyValues("surge.env=prod", pw("a".repeat(15))).run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", pw("a".repeat(16))).run(ctx -> assertThat(ctx).hasNotFailed());
        // 16 spaces is blank, not a password
        runner.withPropertyValues("surge.env=prod", pw(" ".repeat(16))).run(ctx -> assertThat(ctx).hasFailed());
        // 15 code points that are 30 UTF-8 bytes must not count as 16
        runner.withPropertyValues("surge.env=prod", pw("é".repeat(15))).run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", pw("é".repeat(16))).run(ctx -> assertThat(ctx).hasNotFailed());
        // a surrogate pair is one code point
        runner.withPropertyValues("surge.env=prod", pw("😀".repeat(15))).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void passwordsOver72BytesFailInEveryEnvironment() {
        for (String env : new String[] {"prod", "dev"}) {
            runner.withPropertyValues("surge.env=" + env, pw("a".repeat(72))).run(ctx -> assertThat(ctx).hasNotFailed());
            runner.withPropertyValues("surge.env=" + env, pw("a".repeat(73))).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                        "SURGE_ADMIN_PASSWORD must be at most 72 bytes in UTF-8");
            });
        }
        // 37 two-byte characters = 74 bytes
        runner.withPropertyValues("surge.env=prod", pw("é".repeat(37))).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void prodWithAMissingPasswordFailsToStart() {
        runner.withPropertyValues("surge.env=prod").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", pw("")).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void prodWithTestTokensEnabledFailsToStart() {
        runner.withPropertyValues("surge.env=prod", pw(STRONG), "surge.auth.test-tokens.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "surge.auth.test-tokens.enabled must be false when SURGE_ENV=prod");
        });
    }

    @Test
    void prodWithAStrongPasswordStartsAndUsesIt() {
        runner.withPropertyValues("surge.env=prod", pw(STRONG)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StartupGuards.class).adminPassword()).isEqualTo(STRONG);
        });
    }

    @Test
    void devFallsBackToTheLocalDefaultAndAllowsTestTokens() {
        runner.withPropertyValues("surge.env=dev", "surge.auth.test-tokens.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StartupGuards.class).adminPassword()).isEqualTo("surge-admin");
        });
    }

    @Test
    void environmentNamesAreExact() {
        for (String env : new String[] {"production", "PROD", "Dev"}) {
            runner.withPropertyValues("surge.env=" + env, pw(STRONG)).run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Test
    void devOnAPublicDeploymentFailsToStart() {
        runner.withPropertyValues("surge.env=dev", "surge.security.public-deployment=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage("SURGE_ENV=dev is not allowed when "
                    + "surge.security.public-deployment=true; set SURGE_ENV=prod");
        });
        runner.withPropertyValues("surge.env=prod", pw(STRONG), "surge.security.public-deployment=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void adminEmailMustBeAnEmail() {
        for (String email : new String[] {"", "   ", "not-an-email", "a@b", "two words@example.test"}) {
            new ApplicationContextRunner().withUserConfiguration(StartupGuards.class)
                    .withPropertyValues("surge.env=dev", "surge.auth.admin-email=" + email)
                    .run(ctx -> assertThat(ctx).as(email).hasFailed());
        }
    }

    /** The compose switch for the loadtest overlay is SURGE_AUTH_TEST_TOKENS; it must reach the property. */
    @Test
    void testTokenEnvVariableBindsToTheProperty() {
        new ApplicationContextRunner().withInitializer(StartupGuardsTest::loadApplicationYml)
                .withSystemProperties("SURGE_AUTH_TEST_TOKENS=true")
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("surge.auth.test-tokens.enabled"))
                        .isEqualTo("true"));
        new ApplicationContextRunner().withInitializer(StartupGuardsTest::loadApplicationYml)
                .run(ctx -> assertThat(ctx.getEnvironment().getProperty("surge.auth.test-tokens.enabled"))
                        .isEqualTo("false"));
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
