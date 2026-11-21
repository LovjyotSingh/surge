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

    /** Broad patterns that would trust far more than nginx: a public address, or several private ones. */
    @Test
    void aPublicDeploymentThatTrustsMoreThanOneProxyAddressDoesNotStart() {
        for (String broad : new String[] {".*[.]10", "\\d+\\.\\d+\\.\\d+\\.10", "10[.]30[.]250[.][0-9]+",
                "8[.]8[.]8[.]8", "1[.]2[.]3[.]4|10[.]30[.]250[.]10", "(192|172|10)[.].*"}) {
            runner.withPropertyValues("surge.security.public-deployment=true", "surge.security.trusted-proxies=" + broad)
                    .run(context -> assertThat(context).as(broad).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("SURGE_TRUSTED_PROXIES"));
        }
    }

    /** The value is a regex over one address, not CIDR: "10.30.250.10/32" would never match and trust nobody. */
    @Test
    void cidrNotationIsRefused() {
        for (String cidr : new String[] {"10.30.250.10/32", "10[.]30[.]250[.]0/24"}) {
            runner.withPropertyValues("surge.security.public-deployment=true", "surge.security.trusted-proxies=" + cidr)
                    .run(context -> assertThat(context).as(cidr).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("CIDR"));
        }
    }

    @Test
    void aPublicDeploymentThatTrustsNginxStarts() {
        for (String exact : new String[] {"10[.]30[.]250[.]10", "10[.]30[.]250[.]11", "172[.]30[.]250[.]10",
                "10[.]30[.]250[.]10|0:0:0:0:0:ffff:a1e:fa0a"}) {
            runner.withPropertyValues("surge.security.public-deployment=true",
                            "surge.security.trusted-proxies=" + exact)
                    .run(context -> assertThat(context).as(exact).hasNotFailed());
        }
    }

    @Test
    void aPrivateDeploymentMayTrustNoProxy() {
        runner.run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("surge.security.public-deployment=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
