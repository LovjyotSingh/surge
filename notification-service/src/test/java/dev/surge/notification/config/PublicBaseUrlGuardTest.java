package dev.surge.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S10: the ticket email links to PUBLIC_BASE_URL, where the reader signs in. On a public deployment that link must be
 * https://: a plain-HTTP link would send the reader's password over the network in clear text (or rely on a redirect
 * a network attacker can strip).
 */
class PublicBaseUrlGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PublicBaseUrlGuard.class);

    @Test
    void aPublicDeploymentRefusesALinkThatIsNotHttps() {
        for (String url : new String[] {"http://surge.example.com", "http://localhost:8080", "", "surge.example.com",
                "ftp://surge.example.com", "https://"}) {
            runner.withPropertyValues("surge.security.public-deployment=true",
                            "surge.notification.public-base-url=" + url)
                    .run(context -> assertThat(context).as(url).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("PUBLIC_BASE_URL"));
        }
    }

    @Test
    void aPublicDeploymentWithAnHttpsLinkStarts() {
        for (String url : new String[] {"https://surge.example.com", "HTTPS://Surge.Example.com/",
                "https://surge.example.com:8443"}) {
            runner.withPropertyValues("surge.security.public-deployment=true",
                            "surge.notification.public-base-url=" + url)
                    .run(context -> assertThat(context).as(url).hasNotFailed());
        }
    }

    @Test
    void aPrivateRunKeepsItsLocalLink() {
        runner.withPropertyValues("surge.notification.public-base-url=http://localhost:8080")
                .run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
