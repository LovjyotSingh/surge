package dev.surge.auth.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import dev.surge.common.security.SurgeEnv;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * The user-JWT keys ({@link JwtKeys}): loaded from {@code SURGE_JWT_KEY_FILE} so tokens survive a restart, generated
 * per start only in dev, refused at startup when missing in prod. The JWKS also serves the optional previous key.
 */
@Configuration(proxyBeanMethods = false)
public class KeyConfig {

    @Bean
    JwtKeys jwtKeys(Environment environment, ResourceLoader loader,
                    @Value("${surge.auth.jwt.key-file:}") String keyFile,
                    @Value("${surge.auth.jwt.previous-key-file:}") String previousKeyFile) {
        return JwtKeys.load(keyFile, previousKeyFile, SurgeEnv.from(environment), loader);
    }

    /** The private signing key. */
    @Bean
    RSAKey signingKey(JwtKeys keys) {
        return keys.current();
    }

    /** Public halves only (current, then previous); this is what the JWKS endpoint serves. */
    @Bean
    JWKSet publicJwkSet(JwtKeys keys) {
        return keys.publicJwkSet();
    }
}
