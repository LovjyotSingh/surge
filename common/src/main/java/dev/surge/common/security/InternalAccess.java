package dev.surge.common.security;

import java.util.function.Predicate;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;

/**
 * Ready-made rules for {@link SurgeAccessRules}, e.g.
 * {@code rules.requestMatchers("/api/bookings/admin/**").access(InternalAccess.gatewayAdmin())}. A rule that fails
 * for an anonymous identity answers 401 {@code missing-user}; for anyone else 403 {@code forbidden}.
 */
public final class InternalAccess {

    private InternalAccess() {
    }

    /** Anything the gateway routed, anonymous included. */
    public static <T> AuthorizationManager<T> fromGateway() {
        return of(InternalIdentity::fromGateway);
    }

    /** A signed-in user's request routed by the gateway. */
    public static <T> AuthorizationManager<T> gatewayUser() {
        return of(identity -> identity.fromGateway() && !identity.isAnonymous());
    }

    /** A signed-in user's request routed by the gateway with the exact {@code ADMIN} role. */
    public static <T> AuthorizationManager<T> gatewayAdmin() {
        return of(InternalIdentity::isGatewayAdmin);
    }

    /** A call made by service {@code issuer} itself ({@code svc:notification}): issuer and subject both match. */
    public static <T> AuthorizationManager<T> service(String issuer) {
        if (!InternalTokens.validIssuer(issuer) || InternalTokens.GATEWAY.equals(issuer)) {
            throw new IllegalArgumentException("Not a service issuer: " + issuer);
        }
        return of(identity -> issuer.equals(identity.issuer()) && issuer.equals(identity.subject()));
    }

    private static <T> AuthorizationManager<T> of(Predicate<InternalIdentity> rule) {
        return (authentication, object) -> new AuthorizationDecision(
                InternalIdentityAuthenticationConverter.identityOf(authentication.get()).filter(rule).isPresent());
    }
}
