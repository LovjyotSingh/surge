package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mints internal identity tokens with this issuer's private P-256 key (ES256, {@code kid} = issuer, 60 s lifetime).
 * Thread-safe and stateless per call: nothing about one caller is kept for the next. Pure Nimbus, no servlet or
 * reactive types, so the gateway and the servlet services share it.
 */
public final class InternalTokenSigner {

    private final String issuer;
    private final ECDSASigner signer;
    private final JWSHeader header;
    private final Clock clock;

    public InternalTokenSigner(ECKey key, String issuer, Clock clock) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(clock, "clock");
        if (!InternalTokens.validIssuer(issuer)) {
            throw new IllegalArgumentException("Not an internal identity issuer name: " + issuer);
        }
        if (!key.isPrivate()) {
            throw new IllegalArgumentException("The signing key for " + issuer + " has no private part");
        }
        if (!Curve.P_256.equals(key.getCurve())) {
            throw new IllegalArgumentException("The signing key for " + issuer + " must be on curve P-256");
        }
        if (!issuer.equals(key.getKeyID())) {
            throw new IllegalArgumentException("The signing key's kid must be the issuer name '" + issuer
                    + "' but is '" + key.getKeyID() + "'");
        }
        try {
            this.signer = new ECDSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Unusable signing key for " + issuer);
        }
        this.issuer = issuer;
        this.clock = clock;
        this.header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(issuer).build();
    }

    public String issuer() {
        return issuer;
    }

    /**
     * @param audience  the target service's {@code spring.application.name}
     * @param subject   user id or {@code svc:<name>}; null for an anonymous token
     * @param roles     exact upper-case role tokens; empty for anonymous
     * @param requestId the current request id; left out when null or not a well-formed id
     */
    public String sign(String audience, String subject, Collection<String> roles, String requestId) {
        if (audience == null || !InternalTokens.AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("Not a service name: " + audience);
        }
        if (subject != null && !InternalTokens.SUBJECT.matcher(subject).matches()) {
            throw new IllegalArgumentException("Refusing to sign a malformed subject");
        }
        List<String> roleList = new ArrayList<>();
        for (String role : roles == null ? List.<String>of() : roles) {
            if (role == null || !InternalTokens.ROLE.matcher(role).matches()) {
                throw new IllegalArgumentException("Refusing to sign a malformed role");
            }
            if (!roleList.contains(role)) {
                roleList.add(role);
            }
        }
        // NumericDate has second precision; truncating here keeps exp - iat exactly MAX_LIFETIME.
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(InternalTokens.MAX_LIFETIME)))
                .jwtID(UUID.randomUUID().toString())
                .claim(InternalTokens.ROLES, roleList);
        if (subject != null) {
            claims.subject(subject);
        }
        if (requestId != null && InternalTokens.REQUEST_ID_VALUE.matcher(requestId).matches()) {
            claims.claim(InternalTokens.REQUEST_ID, requestId);
        }
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign an internal identity token", e);
        }
        return jwt.serialize();
    }

    /** Signs {@code identity}, whose issuer must be this signer's. */
    public String sign(InternalIdentity identity) {
        if (!issuer.equals(identity.issuer())) {
            throw new IllegalArgumentException("This signer is '" + issuer + "', not '" + identity.issuer() + "'");
        }
        return sign(identity.audience(), identity.subject(), identity.roles(), identity.requestId());
    }
}
