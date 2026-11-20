package dev.surge.gateway.ratelimit;

import dev.surge.gateway.error.ProblemWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Redis token-bucket limiter that answers a denied request with a {@code rate-limited} problem and {@code Retry-After}
 * (Gateway's stock {@code RequestRateLimiter} can only produce a bare 429). A request with no key (no verified user, no
 * address) is refused rather than let through unmetered. It runs once per request, so on the SSE route it limits how
 * often a stream is opened, never the events of an open stream.
 *
 * <p>When Redis fails, the stock {@link RedisRateLimiter} lets the request through and reports {@code -1} tokens
 * left. What happens then is the bucket's {@link OnLimiterFailure}: {@code ALLOW} (fail open, the stock behaviour, for
 * reads, holds, orders and tickets) or {@code REFUSE} (fail closed, 503 {@code service-unavailable}, for login, signup
 * and email verification, so a Redis outage cannot lift the limits on password guessing, code guessing or sending
 * email). Signed-in requests need Redis anyway: the token denylist fails closed without it.
 */
public class RateLimitFilter implements GatewayFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String UNAVAILABLE_RETRY_AFTER = "5";

    /** What a bucket does when the limiter cannot reach Redis. */
    public enum OnLimiterFailure {
        ALLOW, REFUSE
    }

    /**
     * A token bucket: {@code replenishRate} tokens per second, at most {@code burstCapacity}, and each request costs
     * {@code requestedTokens}. Gateway's limiter counts whole tokens per second, so a rate below one request per
     * second is a cost above one ({@link #perMinute}).
     */
    public record Limit(int replenishRate, int burstCapacity, int requestedTokens) {

        public Limit {
            if (replenishRate < 1 || requestedTokens < 1 || burstCapacity < requestedTokens) {
                throw new IllegalArgumentException("invalid limit");
            }
        }

        /** {@code rate} requests per second, bursts of up to {@code burst}. */
        public static Limit perSecond(int rate, int burst) {
            return new Limit(rate, burst, 1);
        }

        /** {@code requests} per minute, also the burst: one token per second, each request costs 60/requests. */
        public static Limit perMinute(int requests) {
            if (requests < 1 || 60 % requests != 0) {
                throw new IllegalArgumentException("requests per minute must divide 60");
            }
            int cost = 60 / requests;
            return new Limit(1, requests * cost, cost);
        }

        /** Time for one request's tokens to refill, at least one second. */
        int retryAfterSeconds() {
            return Math.max(1, (int) Math.ceil((double) requestedTokens / replenishRate));
        }
    }

    private final RedisRateLimiter limiter;
    private final String bucket;
    private final KeyResolver keyResolver;
    private final ProblemWriter problems;
    private final Limit limit;
    private final OnLimiterFailure onFailure;

    /** A bucket that fails open when Redis is unreachable. */
    public RateLimitFilter(RedisRateLimiter limiter, ProblemWriter problems, String bucket, KeyResolver keyResolver,
            Limit limit) {
        this(limiter, problems, bucket, keyResolver, limit, OnLimiterFailure.ALLOW);
    }

    /**
     * @param bucket names the bucket and the limiter configuration entry; it prefixes the key so buckets of different
     *               routes never share tokens
     */
    public RateLimitFilter(RedisRateLimiter limiter, ProblemWriter problems, String bucket, KeyResolver keyResolver,
            Limit limit, OnLimiterFailure onFailure) {
        if (!limiter.isIncludeHeaders()) {
            // the remaining-tokens header is the only sign that Redis failed (limiterFailed): without it, a bucket
            // meant to fail closed would silently fail open
            throw new IllegalStateException("RedisRateLimiter must include its headers (includeHeaders=true)");
        }
        this.limiter = limiter;
        this.problems = problems;
        this.bucket = bucket;
        this.keyResolver = keyResolver;
        this.limit = limit;
        this.onFailure = onFailure;
        limiter.getConfig().put(bucket, new RedisRateLimiter.Config().setReplenishRate(limit.replenishRate())
                .setBurstCapacity(limit.burstCapacity()).setRequestedTokens(limit.requestedTokens()));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return keyResolver.resolve(exchange).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(maybeKey -> {
                    if (maybeKey.isEmpty()) {
                        return problems.write(exchange, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                                "Cannot identify the caller");
                    }
                    return limiter.isAllowed(bucket, bucket + ":" + maybeKey.get())
                            .onErrorResume(e -> Mono.just(new RateLimiter.Response(true,
                                    Map.of(limiter.getRemainingHeader(), "-1"))))
                            // The rest of the request (later filters, routing) runs off the Redis client's reply
                            // thread, so it never delays other requests' replies (see TokenDenylist#isRevoked).
                            .publishOn(Schedulers.parallel())
                            .flatMap(response -> decide(exchange, chain, response));
                });
    }

    private Mono<Void> decide(ServerWebExchange exchange, GatewayFilterChain chain, RateLimiter.Response response) {
        Map<String, String> headers = new LinkedHashMap<>(response.getHeaders());
        if (limiterFailed(headers)) {
            if (onFailure == OnLimiterFailure.REFUSE) {
                log.warn("Rate limiter unavailable; refusing {} (bucket {} fails closed)",
                        exchange.getRequest().getPath(), bucket);
                return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                        "Service unavailable", "This is temporarily unavailable; retry shortly",
                        Map.of("Retry-After", UNAVAILABLE_RETRY_AFTER));
            }
            return chain.filter(exchange);
        }
        if (response.isAllowed()) {
            headers.forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
            return chain.filter(exchange);
        }
        headers.put("Retry-After", String.valueOf(limit.retryAfterSeconds()));
        return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
                "Too many requests", "Rate limit exceeded, retry shortly", headers);
    }

    /**
     * The stock limiter's only failure signal: on any Redis error it answers "allowed" with {@code -1} tokens left (a
     * real answer is never negative). Covered by {@code EdgeLimitsTest}, so a Gateway upgrade that changes it fails
     * there.
     */
    private boolean limiterFailed(Map<String, String> headers) {
        return "-1".equals(headers.get(limiter.getRemainingHeader()));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
