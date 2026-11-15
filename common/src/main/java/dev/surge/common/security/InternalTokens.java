package dev.surge.common.security;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The internal identity token protocol in one place (audit §3). A token is a compact ES256 JWS sent in
 * {@value #HEADER}; its header {@code kid} is the issuer name, and its claims are {@code iss}, {@code aud} (the target
 * {@code spring.application.name}), optional {@code sub}, {@code roles} (string array), {@code iat}, {@code exp}
 * ({@code iat} + 60 s), {@code jti} and optional {@code rid} (request id).
 */
public final class InternalTokens {

    /** The request header that carries the token. Nothing else (Authorization, X-User-*) is ever read. */
    public static final String HEADER = "X-Surge-Identity";

    public static final String GATEWAY = "gateway";
    public static final String SVC_BOOKING = "svc:booking";
    public static final String SVC_NOTIFICATION = "svc:notification";
    /** Every issuer that exists. A verifier accepts these by default; a service can narrow the list. */
    public static final Set<String> KNOWN_ISSUERS = Set.of(GATEWAY, SVC_BOOKING, SVC_NOTIFICATION);

    /** The only role that unlocks admin endpoints, compared as an exact token. */
    public static final String ADMIN = "ADMIN";

    /** {@code exp - iat} is exactly this when signed and at most this when verified; also the maximum token age. */
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    /** Tolerated clock difference between signer and verifier (future {@code iat}, past {@code exp}). */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    static final String ROLES = "roles";
    static final String REQUEST_ID = "rid";
    /** Far more than a real token (about 450 bytes); anything longer is refused before parsing. */
    static final int MAX_TOKEN_LENGTH = 4096;

    static final Pattern ISSUER = Pattern.compile("^(gateway|svc:[a-z][a-z0-9-]{0,31})$");
    /** User ids ({@code u-42}, {@code lt-...}) and service subjects ({@code svc:notification}). */
    static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$|^svc:[a-z][a-z0-9-]{0,31}$");
    static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z_]{0,31}$");
    static final Pattern AUDIENCE = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
    /** Same rule as the gateway's request-id filter. */
    static final Pattern REQUEST_ID_VALUE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    static final Pattern COMPACT_JWS = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private InternalTokens() {
    }

    static boolean validIssuer(String issuer) {
        return issuer != null && ISSUER.matcher(issuer).matches();
    }
}
