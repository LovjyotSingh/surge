package dev.surge.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S10: on a public deployment the gateway must know which peer is nginx. With {@code surge.security.trusted-proxies}
 * blank every client would share nginx's address, so one attacker's failed logins would lock everyone out of login and
 * signup (SEC-11); a pattern that trusts any address would let any client choose its own rate-limit key.
 */
class TrustedProxiesGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TrustedProxiesGuard.class);

    @Test
    void aPublicDeploymentWithoutTrustedProxiesDoesNotStart() {
        runner.withPropertyValues("surge.security.public-deployment=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SURGE_TRUSTED_PROXIES"));
        runner.withPropertyValues("surge.security.public-deployment=true", "surge.security.trusted-proxies=  ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aPublicDeploymentThatTrustsAnyAddressDoesNotStart() {
        for (String any : new String[] {".*", ".+", "[0-9.]+|.*", "(.*)"}) {
            runner.withPropertyValues("surge.security.public-deployment=true", "surge.security.trusted-proxies=" + any)
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("SURGE_TRUSTED_PROXIES"));
        }
    }

    @Test
    void aPublicDeploymentThatTrustsNginxStarts() {
        runner.withPropertyValues("surge.security.public-deployment=true",
                        "surge.security.trusted-proxies=10[.]30[.]250[.]10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aPrivateDeploymentMayTrustNoProxy() {
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("surge.security.public-deployment=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
