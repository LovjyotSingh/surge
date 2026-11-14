package dev.surge.gateway.security;

import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that turns a verified JWT into the identity headers the services trust. Whatever the client sent as
 * {@code X-User-Id}, {@code X-User-Email} or {@code X-User-Roles} (and the bearer token itself, which downstream
 * services never need) is ALWAYS removed first; the headers are then set from the token's claims only when the
 * request carries a verified JWT.
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> {
                    ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
                        headers.remove(USER_ID);
                        headers.remove(USER_EMAIL);
                        headers.remove(USER_ROLES);
                        headers.remove("Authorization");
                        authentication.ifPresent(token -> {
                            var jwt = token.getToken();
                            if (JwtClaims.validUserId(jwt.getSubject())) {
                                headers.set(USER_ID, jwt.getSubject());
                                String email = JwtClaims.email(jwt);
                                if (email != null) {
                                    headers.set(USER_EMAIL, email);
                                }
                                var roles = JwtClaims.roles(jwt);
                                if (!roles.isEmpty()) {
                                    headers.set(USER_ROLES, String.join(",", roles));
                                }
                            }
                        });
                    }).build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
