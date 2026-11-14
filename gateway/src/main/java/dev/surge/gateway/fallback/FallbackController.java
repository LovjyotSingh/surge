package dev.surge.gateway.fallback;

import dev.surge.gateway.error.ProblemWriter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Target of the circuit breakers' {@code forward:/fallback/<service>}. Reached only by an internal forward: the
 * security chain denies {@code /fallback/**} from outside.
 */
@RestController
public class FallbackController {

    private final ProblemWriter problems;

    public FallbackController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(ServerWebExchange exchange, @PathVariable String service) {
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                "Service unavailable", "The " + service + " service is temporarily unavailable, try again shortly");
    }
}
