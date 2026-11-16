package dev.surge.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.surge.common.security.InternalIdentity;
import dev.surge.common.security.InternalKeys;
import dev.surge.common.security.InternalTokenVerifier;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeEnv;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.DefaultResourceLoader;
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

    private static final AtomicInteger CLIENTS = new AtomicInteger();

    /** Sends every request as coming from {@link #clientIp} (a request's own X-Forwarded-For header wins). */
    @Autowired
    protected WebTestClient client;

    /** This test's own client address, so per-IP buckets never carry over from another test. */
    protected String clientIp;

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

    @BeforeEach
    void ownClientAddress() {
        clientIp = freshClientIp();
        client = client.mutate().defaultHeader("X-Forwarded-For", clientIp).build();
    }

    /** An IPv4 address (198.18.0.0/15, the benchmarking range) that no other test in this JVM uses. */
    protected static String freshClientIp() {
        int n = CLIENTS.incrementAndGet();
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
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

    protected static String token(String sub, String email, java.util.Map<String, Object> extra, Object roles,
            String issuer, Instant expires, RSAKey signWith) {
        return sign(sub, email, extra, roles, issuer, expires, signWith);
    }

    /** {@code roles} may be a list, a plain string or null (claim omitted); {@code expires} null omits {@code exp}. */
    protected static String token(String sub, String email, Object roles, String issuer, Instant expires,
            RSAKey signWith) {
        return sign(sub, email, java.util.Map.of(), roles, issuer, expires, signWith);
    }

    private static String sign(String sub, String email, java.util.Map<String, Object> extra, Object roles,
            String issuer, Instant expires,
            RSAKey signWith) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer)
                .issueTime(Date.from(Instant.now().minus(10, ChronoUnit.MINUTES))).claim("email", email);
        extra.forEach(claims::claim);
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

    // ---- what a downstream service receives -----------------------------------------------------------------

    private static final JWKSet DEV_JWKS = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV,
            new DefaultResourceLoader());

    /**
     * The one identity a service gets: exactly one {@code X-Surge-Identity}, valid for {@code audience} and signed by
     * the gateway (verified the way a service does, with the committed DEV keys), and no legacy {@code X-User-*}.
     */
    protected static InternalIdentity identityFor(RecordedRequest seen, String audience) {
        assertThat(seen).as("the downstream service received the request").isNotNull();
        assertNoLegacyIdentityHeaders(seen);
        List<String> values = seen.getHeaders().values(InternalTokens.HEADER);
        assertThat(values).hasSize(1);
        return new InternalTokenVerifier(DEV_JWKS, Set.of(InternalTokens.GATEWAY), audience).verify(values.get(0));
    }

    /** S2b: services read only the signed token, so the gateway never forwards an X-User-* header or the bearer. */
    protected static void assertNoLegacyIdentityHeaders(RecordedRequest seen) {
        for (String header : List.of("X-User-Id", "X-User-Email", "X-User-Roles", "Authorization")) {
            assertThat(seen.getHeaders().values(header)).as(header + " reaches no service").isEmpty();
        }
    }

    private static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
