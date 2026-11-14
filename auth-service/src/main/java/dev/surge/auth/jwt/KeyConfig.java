package dev.surge.auth.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Generates the signing key at startup: tokens stop verifying when auth restarts (acceptable locally). */
@Configuration
public class KeyConfig {

    @Bean
    RSAKey signingKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey unnamed = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
    }

    /** Public half only; this is what the JWKS endpoint serves. */
    @Bean
    JWKSet publicJwkSet(RSAKey signingKey) {
        return new JWKSet(signingKey.toPublicJWK());
    }
}
