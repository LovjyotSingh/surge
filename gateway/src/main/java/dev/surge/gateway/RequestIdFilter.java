package dev.surge.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Keeps an incoming {@code X-Request-Id} when it is well formed, otherwise generates one. The id is passed on to the
 * downstream service, echoed on every response (including error responses written by the gateway itself) and stored
 * as an exchange attribute so problem bodies can carry it. Runs before everything else, Spring Security included.
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "surge.requestId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        exchange.getAttributes().put(ATTRIBUTE, id);
        // beforeCommit: overwrite whatever the downstream service echoed, so the header appears exactly once.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, id);
            return Mono.empty();
        });
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    public static String of(ServerWebExchange exchange) {
        Object id = exchange.getAttribute(ATTRIBUTE);
        return id != null ? id.toString() : "";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
