package dev.surge.common.security;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The verified identity of the current request, for controllers and services (the replacement for reading
 * {@code X-User-*} headers). Works on the request thread and in async processing that Spring Security propagates
 * the context to.
 */
public final class CurrentIdentity {

    private CurrentIdentity() {
    }

    /** The identity, or empty when the request carried no verified identity token. */
    public static Optional<InternalIdentity> get() {
        return InternalIdentityAuthenticationConverter.identityOf(
                SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * The signed-in user's id, as stated by the gateway; 401 {@code missing-user} for an anonymous or missing
     * identity, and for a service identity ({@code svc:*}), which is never a user.
     */
    public static String requireUser() {
        return get().filter(InternalIdentity::isUser)
                .map(InternalIdentity::subject)
                .orElseThrow(InternalIdentityException::missingUser);
    }

    /**
     * The identity of a call made by service {@code issuer} itself ({@code svc:notification}: issuer and subject both
     * that name), for internal endpoints. 401 {@code unauthorized} without an identity; 403 {@code forbidden} for any
     * other caller, gateway users and admins included.
     */
    public static InternalIdentity requireService(String issuer) {
        if (!InternalTokens.isServiceIssuer(issuer)) {
            throw new IllegalArgumentException("Not a service issuer: " + issuer);
        }
        InternalIdentity identity = get().orElseThrow(InternalIdentityException::unauthorized);
        if (!issuer.equals(identity.issuer()) || !issuer.equals(identity.subject())) {
            throw InternalIdentityException.forbidden();
        }
        return identity;
    }

    /**
     * The identity of a signed-in user routed by the gateway with the exact {@code ADMIN} role. 401
     * {@code missing-user} when there is no identity or it is anonymous; 403 {@code forbidden} for anyone else,
     * including a service identity and roles such as {@code SUPERADMIN}.
     */
    public static InternalIdentity requireAdmin() {
        InternalIdentity identity = get().filter(i -> !i.isAnonymous())
                .orElseThrow(InternalIdentityException::missingUser);
        if (!identity.isGatewayAdmin()) {
            throw InternalIdentityException.forbidden();
        }
        return identity;
    }
}
