package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.auth.config.StartupGuards;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Guard logic only (no Boot context, no database): a failing guard must fail context startup. */
class StartupGuardsTest {
    private static final String STRONG = "a-long-random-admin-password";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StartupGuards.class);

    @Test
    void prodWithTheDefaultPasswordFailsToStart() {
        runner.withPropertyValues("surge.env=prod", "surge.auth.admin-password=surge-admin").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "SURGE_ADMIN_PASSWORD must be set to a non-default value of at least 16 characters when SURGE_ENV=prod");
        });
    }

    @Test
    void prodWithAShortOrMissingPasswordFailsToStart() {
        runner.withPropertyValues("surge.env=prod", "surge.auth.admin-password=short-pw-15chrs").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("surge.env=prod", "surge.auth.admin-password=").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void prodWithTestTokensEnabledFailsToStart() {
        runner.withPropertyValues("surge.env=prod", "surge.auth.admin-password=" + STRONG,
                "surge.auth.test-tokens.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "surge.auth.test-tokens.enabled must be false when SURGE_ENV=prod");
        });
    }

    @Test
    void prodWithAStrongPasswordStartsAndUsesIt() {
        runner.withPropertyValues("surge.env=prod", "surge.auth.admin-password=" + STRONG).run(ctx -> {
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
    void unknownEnvironmentNamesFailToStart() {
        runner.withPropertyValues("surge.env=production", "surge.auth.admin-password=" + STRONG)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
