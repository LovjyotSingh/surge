package dev.surge.common.security;

/**
 * An internal identity token that must not be trusted. {@link #reason()} says which rule failed; the message carries
 * only that code, never anything the token contained.
 */
public class InvalidInternalTokenException extends RuntimeException {

    /** Which verification rule rejected the token. */
    public enum Reason {
        /** Not a compact JWS, over-long, or claims that do not parse. */
        MALFORMED,
        /** {@code alg} is not ES256. */
        ALGORITHM,
        /** {@code typ} is not {@value InternalTokens#TYPE}. */
        TYPE,
        /** The header carries {@code crit}, {@code jwk}, {@code jku}, {@code x5u} or {@code x5c}. */
        UNSUPPORTED_HEADER,
        /** {@code kid} is missing or not an allowed issuer. */
        ISSUER_NOT_ALLOWED,
        /** No key with that {@code kid} verifies the signature. */
        SIGNATURE,
        /** {@code iss} differs from {@code kid}. */
        ISSUER_MISMATCH,
        /** {@code aud} is not exactly this service. */
        AUDIENCE,
        /** {@code iat}, {@code exp} or {@code jti} is missing. */
        MISSING_CLAIM,
        /** {@code exp} is not after {@code iat}, or more than 60 s after it. */
        LIFETIME,
        /** {@code iat} more than 5 s in the future, or {@code nbf} not reached. */
        NOT_YET_VALID,
        /** Past {@code exp} (5 s skew) or older than 60 s. */
        EXPIRED,
        /** {@code sub} is malformed, or a gateway token names a service. */
        SUBJECT,
        /** {@code roles} is not an array of exact upper-case role tokens. */
        ROLES,
        /** {@code rid} is malformed. */
        REQUEST_ID,
        /** A service-issued token whose {@code sub} is not its issuer, or that carries roles. */
        SERVICE_IMPERSONATION,
        /** {@code ev} is not a boolean, or is on a token that is not a gateway user's. */
        EMAIL_VERIFIED
    }

    private final Reason reason;

    public InvalidInternalTokenException(Reason reason) {
        super("Invalid internal identity token: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
