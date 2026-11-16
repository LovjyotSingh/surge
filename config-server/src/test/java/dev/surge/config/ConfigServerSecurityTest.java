package dev.surge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.userdetails.UserDetailsService;

/** Where the config server's Basic credentials come from: CONFIG_USER / CONFIG_PASSWORD, dev defaults, prod refusals. */
@ExtendWith(OutputCaptureExtension.class)
class ConfigServerSecurityTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(ConfigServerSecurity.class);

    @Test
    void prodRefusesToStartWithoutCredentials() {
        runner.withPropertyValues("surge.env=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessage("CONFIG_USER and CONFIG_PASSWORD must be set when SURGE_ENV=prod");
        });
    }

    @Test
    void prodRefusesTheDevelopmentPassword() {
        runner.withPropertyValues("surge.env=prod", "surge.config-server.username=config",
                "surge.config-server.password=dev-only-config-password").run(context -> assertThat(
                        context.getStartupFailure()).rootCause().hasMessage(
                                "CONFIG_PASSWORD must not be the development password when SURGE_ENV=prod"));
    }

    @Test
    void prodRefusesAPasswordShorterThan16Characters() {
        runner.withPropertyValues("surge.env=prod", "surge.config-server.username=config",
                "surge.config-server.password=fifteen-chars-x").run(context -> assertThat(context.getStartupFailure())
                        .rootCause().hasMessage("CONFIG_PASSWORD must be at least 16 characters when SURGE_ENV=prod"));
    }

    @Test
    void onlyUrlSafeCharactersAreAccepted() {
        runner.withPropertyValues("surge.config-server.username=config",
                "surge.config-server.password=quote\"d-long-enough").run(context -> assertThat(
                        context.getStartupFailure()).rootCause().hasMessage(
                                "CONFIG_USER and CONFIG_PASSWORD may contain only letters, digits and . _ ~ -"));
    }

    @Test
    void prodWithCredentialsStarts() {
        runner.withPropertyValues("surge.env=prod", "surge.config-server.username=config",
                "surge.config-server.password=s3cret-from-a-secret-file").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("config").getPassword())
                            .startsWith("{bcrypt}");
                });
    }

    @Test
    void devWithoutCredentialsUsesTheDevelopmentOnesAndWarns(CapturedOutput output) {
        runner.run(context -> assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("surge-dev"))
                .isNotNull());
        assertThat(output).contains("CONFIG_USER and CONFIG_PASSWORD are not set")
                .doesNotContain("dev-only-config-password");
    }

    @Test
    void halfACredentialIsRefused() {
        runner.withPropertyValues("surge.config-server.password=only-a-password").run(context -> assertThat(
                context.getStartupFailure()).rootCause().hasMessage(
                        "Set both CONFIG_USER and CONFIG_PASSWORD, or neither (SURGE_ENV=dev only)"));
    }
}
