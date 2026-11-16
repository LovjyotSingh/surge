package dev.surge.booking.support;

import dev.surge.common.security.IdentityTokens;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The {@code X-Surge-Identity} the gateway would send to booking-service, signed fresh for each request with the
 * committed DEV gateway key ({@link IdentityTokens}). Every request in these tests carries one: booking answers 401
 * to anything without a valid token addressed to it (only health is open).
 */
public final class Identity {

    public static final String AUDIENCE = "booking-service";

    private Identity() {
    }

    /** A signed-in user with role USER. */
    public static RequestPostProcessor user(String userId) {
        return IdentityTokens.asOrdinaryUser(AUDIENCE, userId);
    }

    /** A signed-in user (role USER) whose email is not verified yet: holds are refused (S5). */
    public static RequestPostProcessor unverifiedUser(String userId) {
        return IdentityTokens.asUnverifiedUser(AUDIENCE, userId);
    }

    /** A signed-in user with exactly these roles (none: no roles at all). */
    public static RequestPostProcessor userWithRoles(String userId, String... roles) {
        return IdentityTokens.asUser(AUDIENCE, userId, roles);
    }

    /** An administrator {@code u-admin}: roles USER and ADMIN. */
    public static RequestPostProcessor admin() {
        return IdentityTokens.asAdmin(AUDIENCE, "u-admin");
    }

    /** What the gateway sends on public routes (event reads, the seat stream): no user. */
    public static RequestPostProcessor anonymous() {
        return IdentityTokens.asAnonymous(AUDIENCE);
    }

    /** A service-to-service call from {@code issuer} ({@code svc:notification}). */
    public static RequestPostProcessor service(String issuer) {
        return IdentityTokens.asService(issuer, AUDIENCE);
    }
}
