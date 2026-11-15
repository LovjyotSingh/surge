package dev.surge.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Checks {@code surge.env} at startup in every application with {@code common} on its classpath (every service and
 * the gateway, servlet or reactive): it must be exactly {@code dev} or {@code prod}, and {@code dev} is refused when
 * {@code surge.security.public-deployment=true}. The result is a {@link SurgeEnv} bean.
 */
@AutoConfiguration
public class SurgeEnvironmentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SurgeEnv surgeEnv(Environment environment) {
        return SurgeEnv.from(environment);
    }
}
