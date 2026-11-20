package dev.surge.gateway.fallback;

import dev.surge.gateway.error.ProblemWriter;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.util.Map;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Target of the circuit breakers' {@code forward:/fallback/<service>}. Reached only by an internal forward: the
 * security chain denies {@code /fallback/**} from outside. The breaker leaves the exception that sent the call here in
 * {@link ServerWebExchangeUtils#CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR}.
 *
 * <ul>
 *   <li>The breaker's bulkhead was full ({@code config.BulkheadConfig}): the service is fine but has as many calls
 *       from this gateway as it may. 503 {@code service-busy} with {@code Retry-After: 1}, an answer a client should
 *       retry (P2-R26). It comes at once: the bulkhead refuses while subscribing, before any time limiter runs.</li>
 *   <li>Anything else (connection refused, the time limiter, an open breaker): 503 {@code service-unavailable}.</li>
 * </ul>
 */
@RestController
public class FallbackController {

    /** Seconds a client should wait before retrying a {@code service-busy} answer. */
    static final String BUSY_RETRY_AFTER_SECONDS = "1";

    private final ProblemWriter problems;

    public FallbackController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(ServerWebExchange exchange, @PathVariable String service) {
        if (bulkheadWasFull(exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR))) {
            return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "service-busy", "Service busy",
                    "The " + service + " service is handling as many requests as it can right now; retry after "
                            + BUSY_RETRY_AFTER_SECONDS + " s",
                    Map.of(HttpHeaders.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS));
        }
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                "Service unavailable", "The " + service + " service is temporarily unavailable, try again shortly");
    }

    private static boolean bulkheadWasFull(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BulkheadFullException) {
                return true;
            }
        }
        return false;
    }
}
