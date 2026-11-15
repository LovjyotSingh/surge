package dev.surge.gateway.security;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that removes every client-supplied identity before a request is routed: {@code X-User-Id},
 * {@code X-User-Email}, {@code X-User-Roles}, {@code X-Surge-Identity} and the bearer token itself, which downstream
 * services never need. Nothing is set in their place: services trust only the signed {@code X-Surge-Identity} that
 * {@link InternalIdentityFilter} adds next, and no service reads {@code X-User-*} any more (S2b). Whatever needs the
 * caller inside the gateway (the holds/orders rate limiter, {@code KeyResolvers.user()}) reads the verified
 * principal, never a header.
 */
@Component
public class IdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";

    /** Removed from every request, case-insensitively. */
    static final List<String> STRIPPED = List.of(USER_ID, USER_EMAIL, USER_ROLES, InternalIdentityFilter.HEADER,
            HttpHeaders.AUTHORIZATION);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> STRIPPED.forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
