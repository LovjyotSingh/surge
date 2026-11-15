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

    /** The signed-in user's id; 401 {@code missing-user} for an anonymous or missing identity. */
    public static String requireUser() {
        return get().filter(identity -> !identity.isAnonymous())
                .map(InternalIdentity::subject)
                .orElseThrow(InternalIdentityException::missingUser);
    }

    /**
     * The identity of a signed-in user routed by the gateway with the exact {@code ADMIN} role. 401
     * {@code missing-user} when there is no user; 403 {@code forbidden} for anyone else, including a service token
     * that claims {@code ADMIN} and roles such as {@code admin} or {@code SUPERADMIN}.
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
