package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Builds keys and arbitrary (also deliberately broken) tokens for the internal-identity tests. */
final class TestTokens {

    private TestTokens() {
    }

    static ECKey ecKey(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A well-formed claim set: iss, aud, iat, exp, jti and roles [USER] for subject u-1. */
    static JWTClaimsSet.Builder claims(String issuer, String audience, Instant issuedAt, Instant expiresAt) {
        return new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("u-1")
                .claim("roles", List.of("USER")).issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString()).claim("rid", "req-1");
    }

    static String es256(ECKey key, String kid, JWTClaimsSet claims) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims, signer(key));
    }

    static String sign(JWSHeader header, JWTClaimsSet claims, JWSSigner signer) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static JWSSigner signer(ECKey key) {
        try {
            return new ECDSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
