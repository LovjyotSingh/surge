package dev.surge.booking.hold;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@SuppressWarnings("rawtypes")
public class HoldStore {

    private final StringRedisTemplate redis;
    private final RedisScript<List> holdScript;
    private final Clock clock;

    public HoldStore(StringRedisTemplate redis, RedisScript<List> holdScript, Clock clock) {
        this.redis = redis;
        this.holdScript = holdScript;
        this.clock = clock;
    }

    /** Atomically holds all seats or none, via a single Lua script. */
    public HoldResult tryHold(String userId, long eventId, List<Long> seatIds, Duration ttl) {
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(ttl);

        List<String> keys = new ArrayList<>();
        keys.add(RedisKeys.sold(eventId));
        keys.add(RedisKeys.hold(holdId));
        keys.add(RedisKeys.userHold(eventId, userId));
        keys.add(RedisKeys.HOLD_EXPIRY);
        seatIds.forEach(s -> keys.add(RedisKeys.seat(eventId, s)));

        List<String> args = new ArrayList<>();
        args.add(holdId);
        args.add(userId);
        args.add(String.valueOf(eventId));
        args.add(String.valueOf(ttl.toMillis()));
        args.add(String.valueOf(expiresAt.toEpochMilli()));
        args.add(csv(seatIds));
        seatIds.forEach(s -> args.add(String.valueOf(s)));

        List<?> result = redis.execute(holdScript, keys, args.toArray());
        String status = String.valueOf(result.get(0));
        return switch (status) {
            case "OK" -> new HoldResult.Held(holdId, expiresAt);
            case "HOLD_EXISTS" -> new HoldResult.HoldExists(String.valueOf(result.get(1)));
            case "TAKEN" -> new HoldResult.SeatsTaken(
                    result.subList(1, result.size()).stream().map(o -> Long.parseLong(String.valueOf(o))).toList());
            default -> throw new IllegalStateException("Unexpected hold script result: " + status);
        };
    }

    public Optional<ActiveHold> find(String holdId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.hold(holdId));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        List<Long> seats = Arrays.stream(String.valueOf(h.get("seats")).split(",")).map(Long::parseLong).toList();
        return Optional.of(new ActiveHold(holdId, String.valueOf(h.get("userId")),
                Long.parseLong(String.valueOf(h.get("eventId"))), seats,
                Instant.ofEpochMilli(Long.parseLong(String.valueOf(h.get("expiresAt")))),
                String.valueOf(h.get("member"))));
    }

    private static String csv(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }
}
