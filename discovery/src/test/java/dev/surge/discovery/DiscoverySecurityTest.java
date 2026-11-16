package dev.surge.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.userdetails.UserDetailsService;

/** Where Eureka's Basic credentials come from: EUREKA_USER / EUREKA_PASSWORD, dev-only defaults, prod refusals. */
@ExtendWith(OutputCaptureExtension.class)
class DiscoverySecurityTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(DiscoverySecurity.class);

    @Test
    void prodRefusesToStartWithoutCredentials() {
        for (String[] properties : new String[][] {{"surge.env=prod"},
                {"surge.env=prod", "surge.discovery.username=", "surge.discovery.password="},
                {"surge.env=prod", "surge.discovery.username=  ", "surge.discovery.password=  "}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessage("EUREKA_USER and EUREKA_PASSWORD must be set when SURGE_ENV=prod");
            });
        }
    }

    @Test
    void prodRefusesTheDevelopmentPassword() {
        runner.withPropertyValues("surge.env=prod", "surge.discovery.username=eureka",
                "surge.discovery.password=dev-only-eureka-password").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessage("EUREKA_PASSWORD must not be the development password when SURGE_ENV=prod");
                });
    }

    @Test
    void prodRefusesAPasswordShorterThan16Characters() {
        runner.withPropertyValues("surge.env=prod", "surge.discovery.username=eureka",
                "surge.discovery.password=fifteen-chars-x").run(context -> assertThat(context.getStartupFailure())
                        .rootCause().hasMessage("EUREKA_PASSWORD must be at least 16 characters when SURGE_ENV=prod"));
    }

    /** Clients put the pair into their defaultZone URL, so only unreserved URL characters work there. */
    @Test
    void onlyUrlSafeCharactersAreAccepted() {
        for (String[] pair : new String[][] {{"eu@reka", "a-long-enough-password"}, {"eureka", "pass:word-long-enough"},
                {"eureka", "pass%40word-long-enough"}, {"eureka", "pass word long enough"}}) {
            for (String env : new String[] {"dev", "prod"}) {
                runner.withPropertyValues("surge.env=" + env, "surge.discovery.username=" + pair[0],
                        "surge.discovery.password=" + pair[1]).run(context -> assertThat(context.getStartupFailure())
                                .as(pair[0] + " / " + pair[1]).rootCause().hasMessage(
                                        "EUREKA_USER and EUREKA_PASSWORD may contain only letters, digits and . _ ~ -"));
            }
        }
        runner.withPropertyValues("surge.env=prod", "surge.discovery.username=eureka.client_1",
                "surge.discovery.password=Abc-123_xyz.789~qrs").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodWithCredentialsStartsWithThatOneUser() {
        runner.withPropertyValues("surge.env=prod", "surge.discovery.username=eureka",
                "surge.discovery.password=s3cret-from-a-secret-file").run(context -> {
                    assertThat(context).hasNotFailed();
                    UserDetailsService users = context.getBean(UserDetailsService.class);
                    assertThat(users.loadUserByUsername("eureka").getPassword()).startsWith("{bcrypt}")
                            .doesNotContain("s3cret");
                });
    }

    @Test
    void devWithoutCredentialsUsesTheDevelopmentOnesAndWarns(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(UserDetailsService.class).loadUserByUsername("surge-dev")).isNotNull();
        });
        assertThat(output).contains("WARN").contains("EUREKA_USER and EUREKA_PASSWORD are not set")
                .doesNotContain("dev-only-eureka-password");
    }

    @Test
    void halfACredentialIsRefusedEverywhere() {
        for (String env : new String[] {"dev", "prod"}) {
            runner.withPropertyValues("surge.env=" + env, "surge.discovery.username=eureka").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessage("Set both EUREKA_USER and EUREKA_PASSWORD, or neither (SURGE_ENV=dev only)");
            });
        }
    }

    @Test
    void theEnvironmentMustBeDevOrProdAndDevIsNoPublicDeployment() {
        runner.withPropertyValues("surge.env=production").run(context -> assertThat(context.getStartupFailure())
                .rootCause().hasMessage("SURGE_ENV must be exactly 'dev' or 'prod'"));
        runner.withPropertyValues("surge.security.public-deployment=true").run(context -> assertThat(
                context.getStartupFailure()).rootCause().hasMessage(
                        "SURGE_ENV=dev is not allowed when surge.security.public-deployment=true; set SURGE_ENV=prod"));
    }
}
