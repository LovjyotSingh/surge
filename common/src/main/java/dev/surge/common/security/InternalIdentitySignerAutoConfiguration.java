package dev.surge.common.security;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * An {@link InternalTokenSigner} for any application that sets {@code surge.internal-identity.issuer}: servlet,
 * reactive (the gateway) or none. Its key comes from {@code surge.internal-identity.signing-key-location}; see
 * {@link InternalKeys} for the dev fallback and the prod refusals, which run here, at startup.
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.nimbusds.jose.crypto.ECDSASigner")
@ConditionalOnProperty(prefix = "surge.internal-identity", name = "issuer")
@EnableConfigurationProperties(InternalIdentityProperties.class)
public class InternalIdentitySignerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InternalTokenSigner internalTokenSigner(InternalIdentityProperties properties, Environment environment,
            ResourceLoader resourceLoader, ObjectProvider<Clock> clock) {
        SurgeEnv env = SurgeEnv.from(environment);
        String issuer = properties.getIssuer();
        if (!InternalTokens.validIssuer(issuer)) {
            throw new IllegalStateException("surge.internal-identity.issuer must be 'gateway' or 'svc:<name>'");
        }
        return new InternalTokenSigner(
                InternalKeys.loadSigningKey(properties.getSigningKeyLocation(), issuer, env, resourceLoader),
                issuer, clock.getIfUnique(Clock::systemUTC));
    }
}
