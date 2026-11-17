package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
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
import dev.surge.common.security.InvalidInternalTokenException.Reason;
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
 * Checks an internal identity token and returns who it speaks for, or throws {@link InvalidInternalTokenException}
 * with the failed rule as its {@link Reason}. Pure (Nimbus only), so every rule is unit-testable;
 * {@link InternalJwtDecoder} exposes it to Spring Security. A token is accepted only when:
 * <ul>
 *   <li>{@code alg} is ES256 (never {@code none}, HS*, RS*), {@code typ} is {@value InternalTokens#TYPE}, there is no
 *       {@code crit}, and no key of its own ({@code jwk}, {@code jku}, {@code x5u}, {@code x5c});</li>
 *   <li>{@code kid} is an allowed issuer, a key with that id in the JWKS verifies the signature, and {@code iss}
 *       equals {@code kid} (so booking's key can never speak as the gateway);</li>
 *   <li>{@code aud} is exactly one audience: this service's name;</li>
 *   <li>{@code iat}, {@code exp} and {@code jti} are present, {@code exp - iat} is more than 0 and at most 60 s,
 *       {@code iat} is not more than 5 s in the future, {@code exp} has not passed by more than 5 s, the token is at
 *       most 60 s old, and {@code nbf}, if present, has been reached (5 s skew);</li>
 *   <li>a gateway token's {@code sub} is absent (anonymous) or a user id; a service token's {@code sub} is its own
 *       issuer and it has no roles, so a service can never act as a user or claim a role;</li>
 *   <li>{@code roles} and {@code rid} are well formed (exact upper-case role tokens);</li>
 *   <li>{@code ev}, if present, is a boolean on a gateway token with a user {@code sub}: only the gateway states
 *       whether a signed-in user's email is verified.</li>
 * </ul>
 * Several keys may share a {@code kid}, which is how a key is rotated: publish the new key next to the old one, then
 * switch the signer. Time is the system UTC clock, never an application {@code Clock} bean.
 */
public final class InternalTokenVerifier {

    private static final JOSEObjectType TYPE = new JOSEObjectType(InternalTokens.TYPE);

    private final Map<String, List<JWSVerifier>> verifiersByKid;
    private final Set<String> allowedIssuers;
    private final String audience;
    private final Clock clock;

    public InternalTokenVerifier(JWKSet publicKeys, Collection<String> allowedIssuers, String audience) {
        this(publicKeys, allowedIssuers, audience, Clock.systemUTC());
    }

    /** For tests that need a fixed time. */
    InternalTokenVerifier(JWKSet publicKeys, Collection<String> allowedIssuers, String audience, Clock clock) {
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
            throw invalid(Reason.MALFORMED);
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException | RuntimeException e) {
            throw invalid(Reason.MALFORMED);
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())) {
            throw invalid(Reason.ALGORITHM);
        }
        if (!TYPE.equals(header.getType())) {
            throw invalid(Reason.TYPE);
        }
        if (header.getCriticalParams() != null || header.getJWK() != null || header.getJWKURL() != null
                || header.getX509CertURL() != null || header.getX509CertChain() != null) {
            throw invalid(Reason.UNSUPPORTED_HEADER);
        }
        String kid = header.getKeyID();
        if (kid == null || !allowedIssuers.contains(kid)) {
            throw invalid(Reason.ISSUER_NOT_ALLOWED);
        }
        if (!verifiesWithAnyKeyOf(jwt, kid)) {
            throw invalid(Reason.SIGNATURE);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw invalid(Reason.MALFORMED);
        }
        if (!(claims.getClaim("iss") instanceof String issuer) || !issuer.equals(kid)) {
            throw invalid(Reason.ISSUER_MISMATCH);
        }
        checkAudience(claims);
        checkTimes(claims);
        if (!(claims.getClaim("jti") instanceof String jti) || jti.isBlank()) {
            throw invalid(Reason.MISSING_CLAIM);
        }
        String subject = null;
        if (claims.getClaim("sub") != null) {
            if (!(claims.getClaim("sub") instanceof String sub)) {
                throw invalid(Reason.SUBJECT);
            }
            subject = sub;
        }
        Set<String> roles = roles(claims);
        if (InternalTokens.GATEWAY.equals(kid)) {
            if (subject != null && !InternalTokens.isUserId(subject)) {
                throw invalid(Reason.SUBJECT);
            }
        } else if (!kid.equals(subject) || !roles.isEmpty()) {
            throw invalid(Reason.SERVICE_IMPERSONATION);
        }
        String requestId = null;
        if (claims.getClaim(InternalTokens.REQUEST_ID) != null) {
            if (!(claims.getClaim(InternalTokens.REQUEST_ID) instanceof String rid)
                    || !InternalTokens.REQUEST_ID_VALUE.matcher(rid).matches()) {
                throw invalid(Reason.REQUEST_ID);
            }
            requestId = rid;
        }
        Boolean emailVerified = null;
        if (claims.getClaim(InternalTokens.EMAIL_VERIFIED) != null) {
            if (!(claims.getClaim(InternalTokens.EMAIL_VERIFIED) instanceof Boolean ev)
                    || !InternalTokens.GATEWAY.equals(kid) || subject == null) {
                throw invalid(Reason.EMAIL_VERIFIED);
            }
            emailVerified = ev;
        }
        return new Verified(jwt, claims, new InternalIdentity(kid, subject, roles, requestId, audience,
                emailVerified));
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

    /** Exactly one audience, this service: a token minted for several services is replayable at each of them. */
    private void checkAudience(JWTClaimsSet claims) {
        Object aud = claims.getClaim("aud");
        boolean ok = aud instanceof String single ? single.equals(audience)
                : aud instanceof List<?> list && list.size() == 1 && audience.equals(list.get(0));
        if (!ok) {
            throw invalid(Reason.AUDIENCE);
        }
    }

    private void checkTimes(JWTClaimsSet claims) {
        Instant issuedAt = instant(claims, "iat");
        Instant expiresAt = instant(claims, "exp");
        if (issuedAt == null || expiresAt == null) {
            throw invalid(Reason.MISSING_CLAIM);
        }
        if (!expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(InternalTokens.MAX_LIFETIME) > 0) {
            throw invalid(Reason.LIFETIME);
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(InternalTokens.CLOCK_SKEW))) {
            throw invalid(Reason.NOT_YET_VALID);
        }
        if (now.isAfter(expiresAt.plus(InternalTokens.CLOCK_SKEW))
                || Duration.between(issuedAt, now).compareTo(InternalTokens.MAX_LIFETIME) > 0) {
            throw invalid(Reason.EXPIRED);
        }
        if (claims.getClaim("nbf") != null) {
            Instant notBefore = instant(claims, "nbf");
            if (notBefore == null || notBefore.isAfter(now.plus(InternalTokens.CLOCK_SKEW))) {
                throw invalid(Reason.NOT_YET_VALID);
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
            throw invalid(Reason.ROLES);
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String role) || !InternalTokens.isRole(role)) {
                throw invalid(Reason.ROLES);
            }
            roles.add(role);
        }
        return roles;
    }

    private static InvalidInternalTokenException invalid(Reason reason) {
        return new InvalidInternalTokenException(reason);
    }

    /** A token that passed every check, with its parsed parts. */
    record Verified(SignedJWT jwt, JWTClaimsSet claims, InternalIdentity identity) {
    }
}
