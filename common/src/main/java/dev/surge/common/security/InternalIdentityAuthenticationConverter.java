package dev.surge.common.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified token into the request's authentication: a {@link JwtAuthenticationToken} whose authorities are
 * {@code ROLE_<role>} for each exact role, whose name is the subject (or {@code anonymous}), and whose details are the
 * {@link InternalIdentity} (issuer included) that {@link CurrentIdentity} and {@link InternalAccess} read.
 */
public final class InternalIdentityAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String ANONYMOUS_NAME = "anonymous";

    private final String audience;

    public InternalIdentityAuthenticationConverter(String audience) {
        this.audience = audience;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(InternalTokens.ROLES);
        InternalIdentity identity = new InternalIdentity(jwt.getClaimAsString("iss"), jwt.getSubject(),
                roles == null ? null : new LinkedHashSet<>(roles), jwt.getClaimAsString(InternalTokens.REQUEST_ID),
                audience);
        List<GrantedAuthority> authorities = new ArrayList<>();
        identity.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities,
                identity.isAnonymous() ? ANONYMOUS_NAME : identity.subject());
        authentication.setDetails(identity);
        return authentication;
    }

    /** The identity an authentication made by this converter carries; empty for anything else. */
    static Optional<InternalIdentity> identityOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && token.getDetails() instanceof InternalIdentity identity) {
            return Optional.of(identity);
        }
        return Optional.empty();
    }
}
