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
     * First hop of {@code X-Forwarded-For} (set by nginx, which must overwrite rather than append it), else the
     * socket's remote address.
     */
    public static KeyResolver clientIp() {
        return exchange -> Mono.justOrEmpty(clientIp(exchange));
    }

    static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null) {
            String first = forwarded.split(",", 2)[0].trim();
            if (IP_LITERAL.matcher(first).matches()) {
                return first;
            }
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
    }
}
