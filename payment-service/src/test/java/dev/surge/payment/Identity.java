package dev.surge.payment;

import dev.surge.common.security.IdentityTokens;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code X-Surge-Identity} tokens addressed to payment-service, signed fresh for each request with the committed DEV
 * keys ({@link IdentityTokens}): booking's own token for the payments API, the gateway's for the admin routes.
 * payment-service answers 401 to anything without a valid token addressed to it (only health is open).
 */
final class Identity {

    static final String AUDIENCE = "payment-service";

    private Identity() {
    }

    /** booking-service calling the payments API (charge, status, refund); S3 makes booking sign this for real. */
    static RequestPostProcessor booking() {
        return IdentityTokens.asService("svc:booking", AUDIENCE);
    }

    /** A service-to-service call from {@code issuer}. */
    static RequestPostProcessor service(String issuer) {
        return IdentityTokens.asService(issuer, AUDIENCE);
    }

    /** A signed-in user (through the gateway) with role USER. */
    static RequestPostProcessor user(String userId) {
        return IdentityTokens.asOrdinaryUser(AUDIENCE, userId);
    }

    /** A signed-in user with exactly these roles (none: no roles at all). */
    static RequestPostProcessor userWithRoles(String userId, String... roles) {
        return IdentityTokens.asUser(AUDIENCE, userId, roles);
    }

    /** An administrator {@code u-admin}: roles USER and ADMIN. */
    static RequestPostProcessor admin() {
        return IdentityTokens.asAdmin(AUDIENCE, "u-admin");
    }

    /** The gateway's anonymous token: no user. */
    static RequestPostProcessor anonymous() {
        return IdentityTokens.asAnonymous(AUDIENCE);
    }
}
