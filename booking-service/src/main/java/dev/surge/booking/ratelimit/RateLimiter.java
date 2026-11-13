package dev.surge.booking.ratelimit;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Redis-backed token bucket; refill and consume happen atomically in Lua using Redis time. */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RateLimiter(StringRedisTemplate redis, RedisScript<List> tokenBucketScript) {
        this.redis = redis;
        this.script = tokenBucketScript;
    }

    public RateDecision tryConsume(String key, int capacity, double refillPerSecond) {
        List<?> r = redis.execute(script, List.of(key), String.valueOf(capacity), String.valueOf(refillPerSecond));
        boolean allowed = ((Number) r.get(0)).longValue() == 1L;
        long retryMillis = ((Number) r.get(1)).longValue();
        return new RateDecision(allowed, allowed ? 0 : Math.max(1, (retryMillis + 999) / 1000));
    }
}
