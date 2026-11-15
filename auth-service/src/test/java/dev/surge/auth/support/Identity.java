package dev.surge.auth.support;

import dev.surge.common.security.IdentityTokens;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The {@code X-Surge-Identity} the gateway would send to auth-service, signed fresh for each request with the
 * committed DEV gateway key ({@link IdentityTokens}). Every request in these tests carries one: auth-service answers
 * 401 to anything without a valid token addressed to it (only health and the JWKS are open).
 */
public final class Identity {

    public static final String AUDIENCE = "auth-service";

    private Identity() {
    }

    /** A signed-in user with role USER. */
    public static RequestPostProcessor user(String userId) {
        return IdentityTokens.asOrdinaryUser(AUDIENCE, userId);
    }

    /** A signed-in user with exactly these roles (none: no roles at all). */
    public static RequestPostProcessor userWithRoles(String userId, String... roles) {
        return IdentityTokens.asUser(AUDIENCE, userId, roles);
    }

    /** An administrator: roles USER and ADMIN. */
    public static RequestPostProcessor admin() {
        return IdentityTokens.asAdmin(AUDIENCE, "u-admin");
    }

    /** What the gateway sends on public routes (signup, login): no user. */
    public static RequestPostProcessor anonymous() {
        return IdentityTokens.asAnonymous(AUDIENCE);
    }

    /** A service-to-service call from {@code issuer} ({@code svc:notification}). */
    public static RequestPostProcessor service(String issuer) {
        return IdentityTokens.asService(issuer, AUDIENCE);
    }
}
