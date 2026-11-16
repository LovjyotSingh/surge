package dev.surge.gateway.ratelimit;

import dev.surge.gateway.security.JwtClaims;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** Rate-limit keys: the verified user's {@code sub}, or the client address ({@link ClientAddress}). */
public final class KeyResolvers {

    private KeyResolvers() {
    }

    /** Subject of the verified JWT; empty when the request is not authenticated (the caller then denies). */
    public static KeyResolver user() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> auth.getName());
    }

    /** The client address: an IPv4 address or an IPv6 /64, honouring X-Forwarded-For only from a trusted proxy. */
    public static KeyResolver clientIp(ClientAddress clients) {
        return exchange -> Mono.justOrEmpty(clients.keyOf(exchange));
    }

    /**
     * Per client address ({@code ip:<address>}), except that a load-test identity gets its own key
     * ({@code lt:<sub>}). The load test sends every buyer from one machine, and the gateway accepts load-test tokens
     * at all only when {@code surge.security.allow-loadtest-subjects=true} (the loadtest overlay), so outside a load
     * test every caller is keyed by address.
     */
    public static KeyResolver clientIpExceptLoadtest(ClientAddress clients) {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .filter(JwtClaims::isLoadtest)
                .map(jwt -> "lt:" + jwt.getSubject())
                .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(clients.keyOf(exchange)).map(ip -> "ip:" + ip)));
    }
}
