package dev.surge.gateway.security;

import dev.surge.common.security.InternalTokenSigner;
import dev.surge.common.security.InternalTokens;
import dev.surge.gateway.RequestIdFilter;
import dev.surge.gateway.config.RouteAudiences;
import java.util.List;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Signs an {@code X-Surge-Identity} token (audit §3) for every routed request, addressed to the route's target
 * service ({@link RouteAudiences}): {@code sub}, {@code roles} and {@code ev} (email verified, false unless the JWT
 * says exactly {@code true}) from the verified user JWT, or an anonymous token
 * (no {@code sub}, no roles) on public routes. Whatever the client sent in that header is removed first (as
 * {@link IdentityHeadersFilter} also does). A route without an audience fails the request rather than forwarding it
 * unsigned. Runs late, after the route filters (rate limits, circuit breaker and bulkhead) and just before the
 * load-balancer filter, so a refused request is never signed; {@link IdentityHeadersFilter}, which runs first, has
 * already removed anything the client sent. Tokens are minted per request and nothing is cached.
 */
@Component
public class InternalIdentityFilter implements GlobalFilter, Ordered {

    public static final String HEADER = InternalTokens.HEADER;
    /**
     * Just before the load-balancer filter (10,149): after the rate limiters and the circuit breaker's bulkhead, which
     * are route filters (orders 1, 2, ...) and refuse a request without it costing an ES256 signature, and still before
     * the filter that sends the request on ({@code NettyRoutingFilter}, last), so every request that reaches a service
     * is signed.
     */
    public static final int ORDER = ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER - 1;

    private final InternalTokenSigner signer;

    public InternalIdentityFilter(InternalTokenSigner signer) {
        this.signer = signer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String audience = RouteAudiences.of(route);
        if (audience == null) {
            return Mono.error(new IllegalStateException("Route " + (route == null ? "?" : route.getId())
                    + " has no " + RouteAudiences.METADATA_KEY + " metadata; refusing to forward it unsigned"));
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(jwt -> {
                    String token = sign(audience, jwt, RequestIdFilter.of(exchange));
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(headers -> {
                                headers.remove(HEADER);
                                headers.set(HEADER, token);
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    private String sign(String audience, Optional<Jwt> jwt, String requestId) {
        String subject = null;
        List<String> roles = List.of();
        Boolean emailVerified = null;
        if (jwt.isPresent() && JwtClaims.validUserId(jwt.get().getSubject())) {
            subject = jwt.get().getSubject();
            roles = JwtClaims.roles(jwt.get());
            // always stated for a user, so services never have to guess: true only for the boolean true
            emailVerified = JwtClaims.emailVerified(jwt.get());
        }
        return signer.sign(audience, subject, roles, requestId.isEmpty() ? null : requestId, emailVerified);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
