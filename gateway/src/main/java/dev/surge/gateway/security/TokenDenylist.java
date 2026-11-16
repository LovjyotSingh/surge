package dev.surge.gateway.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Revoked user tokens (SEC-18), by {@code jti}, in Redis so every gateway instance sees a logout at once. An entry
 * lives until the token could no longer be accepted anyway ({@code exp} plus the decoder's 60 s clock skew), so the
 * list never grows beyond the tokens that are still alive. Only the {@code jti} is stored, never the token.
 *
 * <p>Fails closed: when Redis cannot answer within {@link #TIMEOUT}, the check errors and the request gets 503
 * rather than letting a possibly revoked token through.
 */
@Component
public class TokenDenylist {

    static final String PREFIX = "surge:jwt:revoked:";
    /** {@code JwtTimestampValidator}'s default skew: a token is still accepted up to 60 s after its {@code exp}. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final ReactiveStringRedisTemplate redis;

    public TokenDenylist(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Boolean> isRevoked(String jti) {
        return redis.hasKey(PREFIX + jti).timeout(TIMEOUT).defaultIfEmpty(Boolean.FALSE);
    }

    /** Denies {@code jti} until {@code expiresAt} (plus the skew); at least one second. */
    public Mono<Void> revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt).plus(CLOCK_SKEW);
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            ttl = Duration.ofSeconds(1);
        }
        return redis.opsForValue().set(PREFIX + jti, "1", ttl).timeout(TIMEOUT)
                .flatMap(ok -> Boolean.TRUE.equals(ok) ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("Redis refused the revocation")));
    }
}
