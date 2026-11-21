package dev.surge.gateway.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Revoked user tokens (SEC-18), by {@code jti}, in Redis so every gateway instance sees a logout at once. An entry
 * lives until the token could no longer be accepted anyway ({@code exp} plus the decoder's 60 s clock skew), so the
 * list never grows beyond the tokens that are still alive. Only the {@code jti} is stored, never the token.
 *
 * <p>Fails closed: when Redis cannot answer within {@link #timeout()}, the check errors and the request gets 503
 * rather than letting a possibly revoked token through.
 */
@Component
public class TokenDenylist {

    static final String PREFIX = "surge:jwt:revoked:";
    /** {@code JwtTimestampValidator}'s default skew: a token is still accepted up to 60 s after its {@code exp}. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final ReactiveStringRedisTemplate redis;
    private final Duration timeout;

    /**
     * @param timeout the deadline for one Redis call: {@code spring.data.redis.timeout} (application.yml), the same
     *                value as Lettuce's command timeout, so the two can never drift apart
     */
    public TokenDenylist(ReactiveStringRedisTemplate redis, @Value("${spring.data.redis.timeout}") Duration timeout) {
        this.redis = redis;
        this.timeout = timeout;
    }

    /** The deadline for one denylist call. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * The rest of the request (the security chain, the internal identity signature, the filters) continues on Reactor's
     * parallel scheduler, never on the Redis client's thread that delivered the reply: there it would delay the replies
     * of every other request queued behind it, and under a sale's burst those passed {@link #timeout()} although Redis had
     * answered in milliseconds (the 10,000-buyer run: 3,315 buyers got 503 from this timeout).
     */
    public Mono<Boolean> isRevoked(String jti) {
        return redis.hasKey(PREFIX + jti).timeout(timeout).defaultIfEmpty(Boolean.FALSE)
                .publishOn(Schedulers.parallel());
    }

    /** Denies {@code jti} until {@code expiresAt} (plus the skew); at least one second. */
    public Mono<Void> revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt).plus(CLOCK_SKEW);
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            ttl = Duration.ofSeconds(1);
        }
        return redis.opsForValue().set(PREFIX + jti, "1", ttl).timeout(timeout)
                .flatMap(ok -> Boolean.TRUE.equals(ok) ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("Redis refused the revocation")));
    }
}
