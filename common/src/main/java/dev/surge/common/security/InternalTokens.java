package dev.surge.common.security;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The internal identity token protocol in one place (see {@code package-info}). A token is a compact ES256 JWS sent
 * in {@value #HEADER}; its header has {@code typ} {@value #TYPE} and {@code kid} = issuer name, and its claims are
 * {@code iss}, {@code aud} (the one target {@code spring.application.name}), {@code sub}, {@code roles} (string
 * array), {@code iat}, {@code exp} ({@code iat} + 60 s), {@code jti} and optional {@code rid} (request id).
 *
 * <p>Gateway tokens carry the user ({@code sub} absent when anonymous) and the user's roles. Service tokens
 * ({@code svc:*}) speak only for the service itself: {@code sub} equals {@code iss} and there are no roles.
 */
public final class InternalTokens {

    /** The request header that carries the token. Nothing else (Authorization, X-User-*) is ever read. */
    public static final String HEADER = "X-Surge-Identity";
    /** The JWS {@code typ} header, so an internal token can never be mistaken for any other JWT (RFC 8725 3.11). */
    public static final String TYPE = "surge-id+jwt";

    public static final String GATEWAY = "gateway";
    public static final String SVC_BOOKING = "svc:booking";
    public static final String SVC_NOTIFICATION = "svc:notification";
    /** Every issuer that exists. A verifier accepts only {@link #GATEWAY} unless a service opts in to more. */
    public static final Set<String> KNOWN_ISSUERS = Set.of(GATEWAY, SVC_BOOKING, SVC_NOTIFICATION);

    /** The only role that unlocks admin endpoints, compared as an exact token. */
    public static final String ADMIN = "ADMIN";

    /**
     * A user id ({@code u-42}, {@code lt-...}). The gateway's {@code JwtClaims} uses this same pattern for the user
     * JWT's {@code sub}, so what the gateway accepts and what it can sign never drift apart.
     */
    public static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** An exact role token ({@code USER}, {@code ADMIN}); shared with the gateway's {@code JwtClaims}. */
    public static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z_]{0,31}$");

    /** {@code exp - iat} is exactly this when signed and at most this when verified; also the maximum token age. */
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    /** Tolerated clock difference between signer and verifier (future {@code iat}, past {@code exp}). */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    static final String ROLES = "roles";
    static final String REQUEST_ID = "rid";
    /** Far more than a real token (about 500 bytes); anything longer is refused before parsing. */
    static final int MAX_TOKEN_LENGTH = 4096;

    static final Pattern ISSUER = Pattern.compile("^(gateway|svc:[a-z][a-z0-9-]{0,31})$");
    static final Pattern AUDIENCE = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    /** Same rule as the gateway's request-id filter. */
    static final Pattern REQUEST_ID_VALUE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    static final Pattern COMPACT_JWS = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private InternalTokens() {
    }

    public static boolean isUserId(String value) {
        return value != null && USER_ID.matcher(value).matches();
    }

    public static boolean isRole(String value) {
        return value != null && ROLE.matcher(value).matches();
    }

    static boolean validIssuer(String issuer) {
        return issuer != null && ISSUER.matcher(issuer).matches();
    }

    static boolean isServiceIssuer(String issuer) {
        return validIssuer(issuer) && !GATEWAY.equals(issuer);
    }
}
