package dev.surge.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Shared fixtures for every gateway test: mock downstream services, a mock JWKS endpoint serving a test RSA key's
 * public half, and token minting. All servers are static and started once, so every test class reuses the one
 * application context (see {@link IntegrationTest}).
 */
@IntegrationTest
public abstract class GatewayTestBase {

    public static final String ISSUER = "surge-auth";
    protected static final String KID = "test-key-1";

    protected static final RSAKey KEY = generate(KID);
    /** Same {@code kid}, different key material: a forged token that claims the right key id. */
    protected static final RSAKey OTHER_KEY = generate(KID);
    /** Different {@code kid} as well. */
    protected static final RSAKey UNKNOWN_KEY = generate("other-kid");

    protected static final Downstream AUTH = new Downstream();
    protected static final Downstream CATALOG = new Downstream();
    protected static final Downstream BOOKING = new Downstream();
    protected static final Downstream NOTIFICATION = new Downstream();
    protected static final Downstream PAYMENT = new Downstream();
    protected static final Downstream JWKS = new Downstream();

    static {
        try {
            AUTH.start();
            CATALOG.start();
            BOOKING.start();
            NOTIFICATION.start();
            PAYMENT.start();
            JWKS.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String jwks = new JWKSet(KEY.toPublicJWK()).toString();
        JWKS.respondWith(request -> new mockwebserver3.MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json").body(jwks).build());
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> JWKS.url() + "/jwks");
        registry.add("surge.routes.auth-uri", AUTH::url);
        registry.add("surge.routes.catalog-uri", CATALOG::url);
        registry.add("surge.routes.booking-uri", BOOKING::url);
        registry.add("surge.routes.notification-uri", NOTIFICATION::url);
        registry.add("surge.routes.payment-uri", PAYMENT::url);
    }

    @Autowired
    protected WebTestClient client;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void resetDownstreams() {
        AUTH.reset();
        CATALOG.reset();
        BOOKING.reset();
        NOTIFICATION.reset();
        PAYMENT.reset();
    }

    // ---- tokens -------------------------------------------------------------------------------------------

    protected static String userToken(String sub) {
        return token(sub, sub + "@surge.test", List.of("USER"), ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), KEY);
    }

    protected static String adminToken(String sub) {
        return token(sub, sub + "@surge.test", List.of("USER", "ADMIN"), ISSUER,
                Instant.now().plus(1, ChronoUnit.HOURS), KEY);
    }

    protected static String tokenWithRoles(String sub, Object roles) {
        return token(sub, sub + "@surge.test", roles, ISSUER, Instant.now().plus(1, ChronoUnit.HOURS), KEY);
    }

    /** {@code roles} may be a list, a plain string or null (claim omitted); {@code expires} null omits {@code exp}. */
    protected static String token(String sub, String email, Object roles, String issuer, Instant expires,
            RSAKey signWith) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer)
                .issueTime(Date.from(Instant.now().minus(10, ChronoUnit.MINUTES))).claim("email", email);
        if (roles != null) {
            claims.claim("roles", roles);
        }
        if (expires != null) {
            claims.expirationTime(Date.from(expires));
        }
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signWith.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(signWith));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
