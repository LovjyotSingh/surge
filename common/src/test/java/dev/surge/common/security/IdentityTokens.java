package dev.surge.common.security;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * TEST-ONLY: signs {@code X-Surge-Identity} tokens the way the gateway (and, from S3, booking and notification) does,
 * with the committed development keys. Shipped to the services in common's {@code test-jar} (test scope only), so it
 * never reaches a service's runtime classpath. It can only ever load the DEV keys, which every service refuses in
 * {@code prod} (by thumbprint), so a token made here is useless against a real deployment.
 *
 * <p>For MockMvc use the {@code as*} request post-processors: they sign a fresh token when each request is built.
 * A token lives 60 s, and a shared test context lives much longer, so never keep a token in a field.
 *
 * <pre>
 * mvc.perform(post("/api/bookings/holds").with(IdentityTokens.asUser("booking-service", "u-1")))
 * </pre>
 */
public final class IdentityTokens {

    /** The request header the token travels in. */
    public static final String HEADER = InternalTokens.HEADER;
    public static final String USER = "USER";
    public static final String ADMIN = InternalTokens.ADMIN;

    private static final Map<String, InternalTokenSigner> SIGNERS = new ConcurrentHashMap<>();

    private IdentityTokens() {
    }

    /** A signer for {@code gateway}, {@code svc:booking} or {@code svc:notification}, with its committed DEV key. */
    public static InternalTokenSigner signer(String issuer) {
        return SIGNERS.computeIfAbsent(issuer, i -> new InternalTokenSigner(
                InternalKeys.loadSigningKey(null, i, SurgeEnv.DEV, new DefaultResourceLoader()), i));
    }

    // ---- tokens --------------------------------------------------------------------------------------------------

    /**
     * A gateway token for signed-in user {@code userId} with exactly {@code roles} (none given: no roles) and a
     * verified email ({@code ev: true}), the usual case; see {@link #unverifiedUser} for the other one.
     */
    public static String user(String audience, String userId, String... roles) {
        return signer(InternalTokens.GATEWAY).sign(audience, userId, List.of(roles), null, true);
    }

    /** A gateway token for an ordinary user (role {@code USER}) who has not verified their email yet. */
    public static String unverifiedUser(String audience, String userId) {
        return signer(InternalTokens.GATEWAY).sign(audience, userId, List.of(USER), null, false);
    }

    /** A gateway token for an ordinary user: role {@code USER}. */
    public static String ordinaryUser(String audience, String userId) {
        return user(audience, userId, USER);
    }

    /** A gateway token for an administrator: roles {@code USER} and {@code ADMIN}. */
    public static String admin(String audience, String userId) {
        return user(audience, userId, USER, ADMIN);
    }

    /** The anonymous token the gateway sends on public routes: no subject, no roles. */
    public static String anonymous(String audience) {
        return signer(InternalTokens.GATEWAY).sign(audience, null, List.of(), null);
    }

    /** A service-to-service token: {@code sub} = {@code iss} = {@code issuer} ({@code svc:booking}), no roles. */
    public static String service(String issuer, String audience) {
        return signer(issuer).signForService(audience, null);
    }

    // ---- unit tests of in-service checks ---------------------------------------------------------------------------

    /**
     * Runs {@code body} with the authentication the identity chain would have set for {@code token} (verified with the
     * DEV JWKS for {@code audience}, any known issuer), then clears the context. For unit tests of the second-layer
     * checks ({@link CurrentIdentity}) that the chain normally hides; {@code token} null runs with no identity.
     */
    public static void runAs(String token, String audience, Runnable body) {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            if (token != null) {
                var decoder = new InternalJwtDecoder(new InternalTokenVerifier(InternalKeys.loadVerificationKeys(
                        null, SurgeEnv.DEV, new DefaultResourceLoader()), InternalTokens.KNOWN_ISSUERS, audience));
                context.setAuthentication(new InternalIdentityAuthenticationConverter(audience)
                        .convert(decoder.decode(token)));
            }
            body.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ---- MockMvc -------------------------------------------------------------------------------------------------

    /** Sends the token {@code token} makes, signed when the request is built. */
    public static RequestPostProcessor with(Supplier<String> token) {
        return request -> {
            request.addHeader(HEADER, token.get());
            return request;
        };
    }

    public static RequestPostProcessor asUser(String audience, String userId, String... roles) {
        String[] copy = Arrays.copyOf(roles, roles.length);
        return with(() -> user(audience, userId, copy));
    }

    public static RequestPostProcessor asOrdinaryUser(String audience, String userId) {
        return with(() -> ordinaryUser(audience, userId));
    }

    public static RequestPostProcessor asUnverifiedUser(String audience, String userId) {
        return with(() -> unverifiedUser(audience, userId));
    }

    public static RequestPostProcessor asAdmin(String audience, String userId) {
        return with(() -> admin(audience, userId));
    }

    public static RequestPostProcessor asAnonymous(String audience) {
        return with(() -> anonymous(audience));
    }

    public static RequestPostProcessor asService(String issuer, String audience) {
        return with(() -> service(issuer, audience));
    }
}
