package dev.surge.gateway.security;

import dev.surge.gateway.error.ProblemWriter;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs before Spring Security. Two jobs:
 * <ul>
 *   <li>Rejects paths that a downstream servlet container might normalise differently from the gateway's matchers
 *       ({@code ..}, encoded dots/slashes/backslashes, {@code ;} parameters, empty segments). Without this,
 *       {@code GET /api/catalog/x/../admin/y} would match the public catalog rule here and become an admin path
 *       downstream.</li>
 *   <li>Answers 404 for {@code /api/payments/**} that is not under {@code /api/payments/admin}: only booking calls
 *       payment, inside the Docker network.</li>
 * </ul>
 */
@Component
public class PathGuardFilter implements WebFilter, Ordered {

    private final ProblemWriter problems;

    public PathGuardFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getURI().getRawPath();
        if (isSuspicious(raw)) {
            return problems.write(exchange, HttpStatus.BAD_REQUEST, "bad-request", "Bad request",
                    "The request path is not allowed");
        }
        if (isHiddenPaymentsPath(raw)) {
            return problems.write(exchange, HttpStatus.NOT_FOUND, "not-found", "Not found", "No such resource");
        }
        return chain.filter(exchange);
    }

    static boolean isSuspicious(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")
                || lower.contains(";") || lower.indexOf('\\') >= 0 || lower.contains("//")) {
            return true;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    static boolean isHiddenPaymentsPath(String path) {
        boolean payments = path.equals("/api/payments") || path.startsWith("/api/payments/");
        boolean admin = path.equals("/api/payments/admin") || path.startsWith("/api/payments/admin/");
        return payments && !admin;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
