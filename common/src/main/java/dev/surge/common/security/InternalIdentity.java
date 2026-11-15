package dev.surge.common.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Who a request comes from, as stated by a verified internal identity token.
 *
 * @param issuer    {@code gateway}, {@code svc:booking} or {@code svc:notification}; for a verified token always equal
 *                  to the key id that signed it
 * @param subject   the user id ({@code u-42}), a service ({@code svc:notification}), or null for an anonymous request
 *                  that the gateway routed to a public endpoint
 * @param roles     exact role tokens such as {@code USER} and {@code ADMIN}; empty when anonymous
 * @param requestId the request id the token was minted for, or null
 * @param audience  the service the token is for (its {@code spring.application.name})
 */
public record InternalIdentity(String issuer, String subject, Set<String> roles, String requestId, String audience) {

    public InternalIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audience, "audience");
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

    public boolean isAnonymous() {
        return subject == null;
    }

    public boolean fromGateway() {
        return InternalTokens.GATEWAY.equals(issuer);
    }

    /**
     * A signed-in user's request, which only the gateway can state. A service identity ({@code svc:*}) is never a
     * user, whatever its subject says.
     */
    public boolean isUser() {
        return fromGateway() && !isAnonymous();
    }

    /** Exact token match: {@code admin} or {@code SUPERADMIN} do not count as {@code ADMIN}. */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** A signed-in user's request routed by the gateway with the exact {@code ADMIN} role. */
    public boolean isGatewayAdmin() {
        return isUser() && hasRole(InternalTokens.ADMIN);
    }
}
