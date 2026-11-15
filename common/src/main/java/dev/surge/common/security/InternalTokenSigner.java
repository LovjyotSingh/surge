package dev.surge.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
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
 * Mints internal identity tokens with this issuer's private P-256 key (ES256, {@code typ} {@value InternalTokens#TYPE},
 * {@code kid} = issuer, 60 s lifetime). It refuses to mint anything a verifier would reject:
 * <ul>
 *   <li>the gateway signs a user id or nothing (anonymous) plus that user's roles;</li>
 *   <li>a service ({@code svc:*}) signs only itself: {@code sub} = its issuer name, no roles. A service can never
 *       speak for a user.</li>
 * </ul>
 * Thread-safe and stateless per call: nothing about one caller is kept for the next. Pure Nimbus, no servlet or
 * reactive types, so the gateway and the servlet services share it. Time is always the system UTC clock, never an
 * application {@code Clock} bean that tests may have frozen.
 */
public final class InternalTokenSigner {

    private final String issuer;
    private final ECDSASigner signer;
    private final JWSHeader header;
    private final Clock clock;

    public InternalTokenSigner(ECKey key, String issuer) {
        this(key, issuer, Clock.systemUTC());
    }

    /** For tests that need a fixed time. */
    InternalTokenSigner(ECKey key, String issuer, Clock clock) {
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
        this.header = new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(InternalTokens.TYPE))
                .keyID(issuer).build();
    }

    public String issuer() {
        return issuer;
    }

    /**
     * @param audience  the target service's {@code spring.application.name}
     * @param subject   gateway: the user id, or null for an anonymous token; a service: its own issuer name
     * @param roles     gateway: exact upper-case role tokens (empty for anonymous); a service: must be empty
     * @param requestId the current request id; left out when null or not a well-formed id
     */
    public String sign(String audience, String subject, Collection<String> roles, String requestId) {
        if (audience == null || !InternalTokens.AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("Not a service name: " + audience);
        }
        List<String> roleList = new ArrayList<>();
        for (String role : roles == null ? List.<String>of() : roles) {
            if (!InternalTokens.isRole(role)) {
                throw new IllegalArgumentException("Refusing to sign a malformed role");
            }
            if (!roleList.contains(role)) {
                roleList.add(role);
            }
        }
        if (InternalTokens.GATEWAY.equals(issuer)) {
            if (subject != null && !InternalTokens.isUserId(subject)) {
                throw new IllegalArgumentException("The gateway signs a user id or no subject");
            }
        } else if (!issuer.equals(subject) || !roleList.isEmpty()) {
            throw new IllegalArgumentException("A service token speaks only for the service: sub must be '" + issuer
                    + "' and roles empty");
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

    /** A service's own token for a call to {@code audience}: {@code sub} = this issuer, no roles. */
    public String signForService(String audience, String requestId) {
        return sign(audience, issuer, List.of(), requestId);
    }

    /** Signs {@code identity}, whose issuer must be this signer's. */
    public String sign(InternalIdentity identity) {
        if (!issuer.equals(identity.issuer())) {
            throw new IllegalArgumentException("This signer is '" + issuer + "', not '" + identity.issuer() + "'");
        }
        return sign(identity.audience(), identity.subject(), identity.roles(), identity.requestId());
    }
}
