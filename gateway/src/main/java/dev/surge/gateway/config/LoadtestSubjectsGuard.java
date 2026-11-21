package dev.surge.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * N7, defense in depth: a public deployment ({@code surge.security.public-deployment=true}, env
 * {@code SURGE_PUBLIC_DEPLOYMENT}) refuses to start with {@code surge.security.allow-loadtest-subjects=true} (env
 * {@code SURGE_ALLOW_LOADTEST_SUBJECTS}). That switch accepts synthetic user tokens ({@code sub} "lt-*" or
 * {@code lt=true}) and keys their per-IP hold limit per subject, so on the open internet it would let anyone holding
 * such a token skip the per-IP limits. The load-test overlay sets it on a private run; the prod overlay never does,
 * and this makes a mixed-up pair of overlays fail at startup instead of going live.
 *
 * <p>Same pattern as {@link TrustedProxiesGuard}: a constructor that throws.
 */
@Configuration(proxyBeanMethods = false)
class LoadtestSubjectsGuard {

    LoadtestSubjectsGuard(@Value("${surge.security.public-deployment:false}") boolean publicDeployment,
            @Value("${surge.security.allow-loadtest-subjects:false}") boolean allowLoadtestSubjects) {
        if (publicDeployment && allowLoadtestSubjects) {
            throw new IllegalStateException("SURGE_ALLOW_LOADTEST_SUBJECTS=true is refused when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true: synthetic load-test identities must never be accepted on a "
                    + "public deployment");
        }
    }
}
