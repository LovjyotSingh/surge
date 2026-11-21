package dev.surge.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** N7: load-test identities and a public deployment never go together. */
class LoadtestSubjectsGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LoadtestSubjectsGuard.class);

    @Test
    void aPublicDeploymentThatAcceptsLoadtestSubjectsDoesNotStart() {
        runner.withPropertyValues("surge.security.public-deployment=true",
                        "surge.security.allow-loadtest-subjects=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SURGE_ALLOW_LOADTEST_SUBJECTS")
                        .hasMessageContaining("SURGE_PUBLIC_DEPLOYMENT"));
    }

    @Test
    void everyOtherCombinationStarts() {
        runner.withPropertyValues("surge.security.public-deployment=true",
                        "surge.security.allow-loadtest-subjects=false")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("surge.security.public-deployment=false",
                        "surge.security.allow-loadtest-subjects=true")
                .run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
