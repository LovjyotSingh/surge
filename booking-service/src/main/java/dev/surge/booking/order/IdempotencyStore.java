package dev.surge.booking.order;

import dev.surge.booking.error.RequestInProgressException;
import dev.surge.booking.hold.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed idempotency keys: {@code IN_PROGRESS} while a checkout runs (short TTL, so a crashed request frees
 * the key soon), then the resulting order id (kept 24 h, the replay window).
 */
@Component
public class IdempotencyStore {

    static final String IN_PROGRESS = "IN_PROGRESS";
    static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(120);
    static final Duration COMPLETED_TTL = Duration.ofHours(24);

    /** Deletes the key only if it still holds IN_PROGRESS, so a completed order id is never removed. */
    private static final RedisScript<Long> ABORT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    /** Compare-and-set: replaces a stale order id with IN_PROGRESS only if the key still holds that order id. */
    private static final RedisScript<Long> TAKE_OVER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end return 0",
            Long.class);

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
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, IN_PROGRESS, IN_PROGRESS_TTL))) {
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

    /**
     * Claims a key whose stored order no longer exists (an admin reset deletes orders but not idempotency keys).
     * Atomic: succeeds only if the key still holds {@code staleOrderId}; false means another request got there first.
     */
    public boolean takeOver(String userId, String key, long staleOrderId) {
        Long changed = redis.execute(TAKE_OVER, List.of(RedisKeys.idempotency(userId, key)),
                String.valueOf(staleOrderId), IN_PROGRESS, String.valueOf(IN_PROGRESS_TTL.toMillis()));
        return changed != null && changed == 1L;
    }

    public void complete(String userId, String key, long orderId) {
        redis.opsForValue().set(RedisKeys.idempotency(userId, key), String.valueOf(orderId), COMPLETED_TTL);
    }

    public void abort(String userId, String key) {
        redis.execute(ABORT, List.of(RedisKeys.idempotency(userId, key)), IN_PROGRESS);
    }
}
