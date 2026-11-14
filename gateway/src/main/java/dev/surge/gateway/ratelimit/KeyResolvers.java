package dev.surge.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Rate-limit keys: the verified user's {@code sub}, or the client IP. */
public final class KeyResolvers {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private KeyResolvers() {
    }

    /** Subject of the verified JWT; empty when the request is not authenticated (the caller then denies). */
    public static KeyResolver user() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> auth.getName());
    }

    /**
     * LAST hop of {@code X-Forwarded-For}: the one the nearest proxy (nginx) appended, which a client cannot choose (a
     * client can only forge the earlier hops). Falls back to the socket address when the header is absent or blank.
     */
    public static KeyResolver clientIp() {
        return exchange -> Mono.justOrEmpty(clientIp(exchange));
    }

    static String clientIp(ServerWebExchange exchange) {
        java.util.List<String> lines = exchange.getRequest().getHeaders().getOrEmpty("X-Forwarded-For");
        if (!lines.isEmpty()) {
            String[] hops = String.join(",", lines).split(",");
            String last = hops[hops.length - 1].trim();
            if (IP_LITERAL.matcher(last).matches()) {
                return last;
            }
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
    }
}
