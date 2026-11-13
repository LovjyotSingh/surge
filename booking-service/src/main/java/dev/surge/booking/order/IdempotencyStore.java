package dev.surge.booking.order;

import dev.surge.booking.error.RequestInProgressException;
import dev.surge.booking.hold.RedisKeys;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed idempotency keys: IN_PROGRESS while a checkout runs, then the resulting order id. */
@Component
public class IdempotencyStore {

    static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Claims the key. Returns the existing order id when this request was already completed (a replay), empty when
     * the caller now owns the key, and throws when another request with the same key is still running.
     */
    public Optional<Long> begin(String userId, String key) {
        String redisKey = RedisKeys.idempotency(userId, key);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, TTL))) {
                return Optional.empty();
            }
            String value = redis.opsForValue().get(redisKey);
            if (value == null) {
                continue; // expired or aborted between SETNX and GET: try to claim again
            }
            if (IN_PROGRESS.equals(value)) {
                throw new RequestInProgressException();
            }
            return Optional.of(Long.parseLong(value));
        }
        throw new RequestInProgressException();
    }

    public void complete(String userId, String key, long orderId) {
        redis.opsForValue().set(RedisKeys.idempotency(userId, key), String.valueOf(orderId), TTL);
    }

    public void abort(String userId, String key) {
        redis.delete(RedisKeys.idempotency(userId, key));
    }
}
