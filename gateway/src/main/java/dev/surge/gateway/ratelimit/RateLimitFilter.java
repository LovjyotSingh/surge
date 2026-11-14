package dev.surge.gateway.ratelimit;

import dev.surge.gateway.error.ProblemWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Redis token-bucket limiter that answers a denied request with a {@code rate-limited} problem and {@code Retry-After}
 * (Gateway's stock {@code RequestRateLimiter} can only produce a bare 429). A request with no key (no verified user, no
 * address) is refused rather than let through unmetered. Redis outages fail open, as in the stock limiter.
 */
public class RateLimitFilter implements GatewayFilter, Ordered {

    private final RedisRateLimiter limiter;
    private final String bucket;
    private final KeyResolver keyResolver;
    private final ProblemWriter problems;
    private final int replenishRate;

    /**
     * @param bucket names the bucket and the limiter configuration entry; it prefixes the key so buckets of different
     *               routes never share tokens
     */
    public RateLimitFilter(RedisRateLimiter limiter, ProblemWriter problems, String bucket, KeyResolver keyResolver,
            int replenishRate, int burstCapacity) {
        this.limiter = limiter;
        this.problems = problems;
        this.bucket = bucket;
        this.keyResolver = keyResolver;
        this.replenishRate = replenishRate;
        limiter.getConfig().put(bucket, new RedisRateLimiter.Config().setReplenishRate(replenishRate)
                .setBurstCapacity(burstCapacity).setRequestedTokens(1));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return keyResolver.resolve(exchange).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(maybeKey -> {
                    if (maybeKey.isEmpty()) {
                        return problems.write(exchange, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                                "Cannot identify the caller");
                    }
                    return limiter.isAllowed(bucket, bucket + ":" + maybeKey.get()).flatMap(response -> {
                        Map<String, String> headers = new LinkedHashMap<>(response.getHeaders());
                        if (response.isAllowed()) {
                            headers.forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
                            return chain.filter(exchange);
                        }
                        headers.put("Retry-After", String.valueOf(retryAfterSeconds()));
                        return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
                                "Too many requests", "Rate limit exceeded, retry shortly", headers);
                    });
                });
    }

    /** Time for one token to refill, at least one second. */
    private int retryAfterSeconds() {
        return Math.max(1, (int) Math.ceil(1.0 / replenishRate));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
