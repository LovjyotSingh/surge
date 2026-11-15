package dev.surge.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** The servlet-service guards in {@link CommonAutoConfiguration}, without a full service. */
class CommonAutoConfigurationTest {

    private static final String REFUSAL = "surge.internal-identity.enforce must be true when SURGE_ENV=prod";

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
            .withPropertyValues("spring.application.name=booking-service");

    @Test
    void prodRefusesAServletServiceThatDoesNotVerifyIdentityTokens() {
        for (String[] properties : new String[][] {
                {"surge.env=prod"},
                {"surge.env=prod", "surge.internal-identity.enforce=false"},
                {"surge.env=prod", "surge.internal-identity.enforce="},
                // only the exact value true turns the chain on (InternalSecurityAutoConfiguration), so only it passes
                {"surge.env=prod", "surge.internal-identity.enforce=yes"},
                {"surge.env=prod", "surge.internal-identity.enforce=1"}}) {
            servlet.withPropertyValues(properties).run(context -> {
                assertThat(context).as(String.join(" ", properties)).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(REFUSAL);
            });
        }
    }

    @Test
    void prodWithEnforcementStarts() {
        servlet.withPropertyValues("surge.env=prod", "surge.internal-identity.enforce=true")
                .run(context -> assertThat(context).hasNotFailed());
        servlet.withPropertyValues("surge.env=prod", "surge.internal-identity.enforce=TRUE")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devMayRunWithoutEnforcement() {
        servlet.run(context -> assertThat(context).hasNotFailed());
        servlet.withPropertyValues("surge.env=dev", "surge.internal-identity.enforce=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anInvalidSurgeEnvIsRefusedHereToo() {
        servlet.withPropertyValues("surge.env=production").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage("SURGE_ENV must be exactly 'dev' or 'prod'");
        });
    }

    @Test
    void onlyServletServicesAreGuarded() {
        // the reactive gateway signs tokens and never verifies them; a non-web app has no requests at all
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
                .withPropertyValues("surge.env=prod").run(context -> assertThat(context).hasNotFailed());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
                .withPropertyValues("surge.env=prod").run(context -> assertThat(context).hasNotFailed());
    }
}
