package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks an internal identity token and returns who it speaks for, or throws {@link InvalidInternalTokenException}.
 * Pure (Nimbus only, injected clock), so every rule is unit-testable; {@link InternalJwtDecoder} exposes it to Spring
 * Security. A token is accepted only when:
 * <ul>
 *   <li>{@code alg} is ES256 (never {@code none}, HS*, RS*), no {@code crit}, and no key of its own
 *       ({@code jwk}, {@code jku}, {@code x5u}, {@code x5c});</li>
 *   <li>{@code kid} is an allowed issuer, a key with that id in the JWKS verifies the signature, and {@code iss}
 *       equals {@code kid} (so booking's key can never speak as the gateway);</li>
 *   <li>{@code aud} contains this service's name;</li>
 *   <li>{@code iat}, {@code exp} and {@code jti} are present, {@code exp - iat} is at most 60 s, {@code iat} is not more
 *       than 5 s in the future, {@code exp} has not passed by more than 5 s, the token is at most 60 s old, and
 *       {@code nbf}, if present, has been reached (5 s skew);</li>
 *   <li>{@code sub}, {@code roles} and {@code rid} are well formed (exact upper-case role tokens).</li>
 * </ul>
 * Several keys may share a {@code kid}, which is how a key is rotated: publish the new key next to the old one, then
 * switch the signer.
 */
public final class InternalTokenVerifier {

    private final Map<String, List<JWSVerifier>> verifiersByKid;
    private final Set<String> allowedIssuers;
    private final String audience;
    private final Clock clock;

    public InternalTokenVerifier(JWKSet publicKeys, Collection<String> allowedIssuers, String audience, Clock clock) {
        Objects.requireNonNull(publicKeys, "publicKeys");
        Objects.requireNonNull(clock, "clock");
        if (audience == null || !InternalTokens.AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("Not a service name: " + audience);
        }
        if (allowedIssuers == null || allowedIssuers.isEmpty()) {
            throw new IllegalArgumentException("At least one issuer must be allowed");
        }
        for (String issuer : allowedIssuers) {
            if (!InternalTokens.validIssuer(issuer)) {
                throw new IllegalArgumentException("Not an internal identity issuer name: " + issuer);
            }
        }
        if (publicKeys.getKeys().isEmpty()) {
            throw new IllegalArgumentException("The internal JWKS holds no keys");
        }
        Map<String, List<JWSVerifier>> verifiers = new HashMap<>();
        for (JWK jwk : publicKeys.getKeys()) {
            ECKey key = requireVerificationKey(jwk);
            try {
                verifiers.computeIfAbsent(key.getKeyID(), kid -> new ArrayList<>()).add(new ECDSAVerifier(key));
            } catch (JOSEException e) {
                throw new IllegalArgumentException("Unusable verification key '" + key.getKeyID() + "'");
            }
        }
        verifiersByKid = new HashMap<>();
        verifiers.forEach((kid, list) -> verifiersByKid.put(kid, List.copyOf(list)));
        this.allowedIssuers = Set.copyOf(allowedIssuers);
        this.audience = audience;
        this.clock = clock;
    }

    /** The JWKS entry rules, shared with key loading: public, EC P-256, a kid, and no other use or algorithm. */
    static ECKey requireVerificationKey(JWK jwk) {
        if (!(jwk instanceof ECKey key) || !Curve.P_256.equals(key.getCurve())) {
            throw new IllegalArgumentException("Every internal JWKS key must be an EC P-256 key ('"
                    + jwk.getKeyID() + "' is not)");
        }
        if (key.isPrivate()) {
            throw new IllegalArgumentException("The internal JWKS must hold public keys only ('" + key.getKeyID()
                    + "' has a private part)");
        }
        if (key.getKeyID() == null || key.getKeyID().isBlank()) {
            throw new IllegalArgumentException("Every internal JWKS key needs a kid (its issuer name)");
        }
        if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
            throw new IllegalArgumentException("Internal JWKS key '" + key.getKeyID() + "' is not a signature key");
        }
        if (key.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("Internal JWKS key '" + key.getKeyID() + "' is not an ES256 key");
        }
        return key;
    }

    public String audience() {
        return audience;
    }

    public InternalIdentity verify(String token) {
        return verifyToken(token).identity();
    }

    /** The verified token with its parsed parts, for the Spring Security adapter. */
    Verified verifyToken(String token) {
        if (token == null || token.isEmpty() || token.length() > InternalTokens.MAX_TOKEN_LENGTH) {
            throw invalid("Malformed identity token");
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException | RuntimeException e) {
            throw invalid("Malformed identity token");
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            throw invalid("Identity token algorithm must be ES256");
        }
        if (header.getCriticalParams() != null || header.getJWK() != null || header.getJWKURL() != null
                || header.getX509CertURL() != null || header.getX509CertChain() != null) {
            throw invalid("Identity token header has unsupported parameters");
        }
        String kid = header.getKeyID();
        if (kid == null || !allowedIssuers.contains(kid)) {
            throw invalid("Identity token issuer is not allowed here");
        }
        if (!verifiesWithAnyKeyOf(jwt, kid)) {
            throw invalid("Identity token signature is not valid");
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw invalid("Malformed identity token claims");
        }
        if (!(claims.getClaim("iss") instanceof String issuer) || !issuer.equals(kid)) {
            throw invalid("Identity token iss must equal its kid");
        }
        checkAudience(claims);
        checkTimes(claims);
        if (!(claims.getClaim("jti") instanceof String jti) || jti.isBlank()) {
            throw invalid("Identity token has no jti");
        }
        String subject = null;
        if (claims.getClaim("sub") != null) {
            if (!(claims.getClaim("sub") instanceof String sub) || !InternalTokens.SUBJECT.matcher(sub).matches()) {
                throw invalid("Identity token subject is malformed");
            }
            subject = sub;
        }
        String requestId = null;
        if (claims.getClaim(InternalTokens.REQUEST_ID) != null) {
            if (!(claims.getClaim(InternalTokens.REQUEST_ID) instanceof String rid)
                    || !InternalTokens.REQUEST_ID_VALUE.matcher(rid).matches()) {
                throw invalid("Identity token request id is malformed");
            }
            requestId = rid;
        }
        Set<String> roles = roles(claims);
        return new Verified(jwt, claims, new InternalIdentity(kid, subject, roles, requestId, audience));
    }

    private boolean verifiesWithAnyKeyOf(SignedJWT jwt, String kid) {
        for (JWSVerifier verifier : verifiersByKid.getOrDefault(kid, List.of())) {
            try {
                if (jwt.verify(verifier)) {
                    return true;
                }
            } catch (JOSEException | RuntimeException e) {
                // not this key; try the next one (rotation)
            }
        }
        return false;
    }

    private void checkAudience(JWTClaimsSet claims) {
        Object aud = claims.getClaim("aud");
        boolean ok = aud instanceof String single ? single.equals(audience)
                : aud instanceof List<?> list && list.contains(audience);
        if (!ok) {
            throw invalid("Identity token is not for this service");
        }
    }

    private void checkTimes(JWTClaimsSet claims) {
        Instant issuedAt = instant(claims, "iat");
        Instant expiresAt = instant(claims, "exp");
        if (issuedAt == null || expiresAt == null) {
            throw invalid("Identity token needs iat and exp");
        }
        if (!expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(InternalTokens.MAX_LIFETIME) > 0) {
            throw invalid("Identity token lifetime is not allowed");
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(InternalTokens.CLOCK_SKEW))) {
            throw invalid("Identity token is issued in the future");
        }
        if (now.isAfter(expiresAt.plus(InternalTokens.CLOCK_SKEW))
                || Duration.between(issuedAt, now).compareTo(InternalTokens.MAX_LIFETIME) > 0) {
            throw invalid("Identity token has expired");
        }
        if (claims.getClaim("nbf") != null) {
            Instant notBefore = instant(claims, "nbf");
            if (notBefore == null || notBefore.isAfter(now.plus(InternalTokens.CLOCK_SKEW))) {
                throw invalid("Identity token is not valid yet");
            }
        }
    }

    private static Instant instant(JWTClaimsSet claims, String name) {
        try {
            Date date = claims.getDateClaim(name);
            return date != null ? date.toInstant() : null;
        } catch (ParseException e) {
            return null;
        }
    }

    private static Set<String> roles(JWTClaimsSet claims) {
        Object claim = claims.getClaim(InternalTokens.ROLES);
        if (claim == null) {
            return Set.of();
        }
        if (!(claim instanceof List<?> values)) {
            throw invalid("Identity token roles must be an array");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String role) || !InternalTokens.ROLE.matcher(role).matches()) {
                throw invalid("Identity token roles are malformed");
            }
            roles.add(role);
        }
        return roles;
    }

    private static InvalidInternalTokenException invalid(String reason) {
        return new InvalidInternalTokenException(reason);
    }

    /** A token that passed every check, with its parsed parts. */
    record Verified(SignedJWT jwt, JWTClaimsSet claims, InternalIdentity identity) {
    }
}
