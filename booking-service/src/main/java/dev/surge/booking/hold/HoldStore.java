package dev.surge.booking.hold;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private final RedisScript<List> finalizeScript;
    private final RedisScript<List> releaseScript;
    private final Clock clock;

    public HoldStore(StringRedisTemplate redis, RedisScript<List> holdScript, RedisScript<List> finalizeScript,
                     RedisScript<List> releaseScript, Clock clock) {
        this.finalizeScript = finalizeScript;
        this.releaseScript = releaseScript;
        this.redis = redis;
        this.holdScript = holdScript;
        this.clock = clock;
    }

    /**
     * Atomically holds all seats or none, via a single Lua script. The locked price of each seat is stored in the
     * same step, so a hold never exists without its prices.
     */
    public HoldResult tryHold(String userId, long eventId, List<HoldLine> lines, Duration ttl) {
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(ttl);
        List<Long> seatIds = lines.stream().map(HoldLine::seatId).toList();

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
        args.add(csv(seatIds.stream().map(String::valueOf).toList()));
        args.add(csv(lines.stream().map(l -> l.priceInr().toPlainString()).toList()));
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
                Long.parseLong(String.valueOf(h.get("eventId"))), seats, prices(seats, h.get("prices")),
                Instant.ofEpochMilli(Long.parseLong(String.valueOf(h.get("expiresAt")))),
                String.valueOf(h.get("member"))));
    }

    /** Number of sold seats for an event, from the Redis sold set (used for surge pricing). */
    public int soldCount(long eventId) {
        Long n = redis.opsForSet().size(RedisKeys.sold(eventId));
        return n == null ? 0 : n.intValue();
    }

    /** Marks the hold's seats sold in Redis and removes the hold. Call only after the DB commit. */
    public void finalizeSold(ActiveHold hold) {
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeys.sold(hold.eventId()));
        keys.add(RedisKeys.hold(hold.holdId()));
        keys.add(RedisKeys.userHold(hold.eventId(), hold.userId()));
        keys.add(RedisKeys.HOLD_EXPIRY);
        hold.seatIds().forEach(s -> keys.add(RedisKeys.seat(hold.eventId(), s)));

        List<String> args = new ArrayList<>();
        args.add(hold.holdId());
        args.add(hold.member());
        hold.seatIds().forEach(s -> args.add(String.valueOf(s)));
        redis.execute(finalizeScript, keys, args.toArray());
    }

    /**
     * Removes the hold WITHOUT selling it: seat keys and the userhold key are deleted only while they still point at
     * this hold, the hold hash is deleted and its holds:expiry member removed. Used when checkout lost the seats.
     */
    public void release(ActiveHold hold) {
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeys.hold(hold.holdId()));
        keys.add(RedisKeys.userHold(hold.eventId(), hold.userId()));
        keys.add(RedisKeys.HOLD_EXPIRY);
        hold.seatIds().forEach(s -> keys.add(RedisKeys.seat(hold.eventId(), s)));
        redis.execute(releaseScript, keys, hold.holdId(), hold.member());
    }

    /** Locked prices by seat; empty when the hash has none (a hold written before prices were stored). */
    private static Map<Long, BigDecimal> prices(List<Long> seats, Object stored) {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        if (stored == null) {
            return prices;
        }
        String[] parts = String.valueOf(stored).split(",");
        if (parts.length != seats.size()) {
            return prices;
        }
        for (int i = 0; i < parts.length; i++) {
            prices.put(seats.get(i), new BigDecimal(parts[i]));
        }
        return prices;
    }

    private static String csv(List<String> values) {
        return String.join(",", values);
    }
}
