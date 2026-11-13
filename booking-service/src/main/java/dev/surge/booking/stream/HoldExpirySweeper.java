package dev.surge.booking.stream;

import dev.surge.booking.event.SeatState;
import dev.surge.booking.hold.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds expire on their own via Redis TTL; this sweeper only notices them so browsers learn the seats are free.
 * The expiry sorted set member is removed atomically in sweep.lua, so concurrent instances never double-announce.
 */
@Component
@SuppressWarnings("rawtypes")
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
    private static final int BATCH = 500;

    private final StringRedisTemplate redis;
    private final RedisScript<List> sweepScript;
    private final SeatEventPublisher publisher;
    private final Clock clock;
    private final Counter expired;

    public HoldExpirySweeper(StringRedisTemplate redis, RedisScript<List> sweepScript, SeatEventPublisher publisher,
                             Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.sweepScript = sweepScript;
        this.publisher = publisher;
        this.clock = clock;
        this.expired = meters.counter("holds_expired_total");
    }

    /** Both delays use the same property so the first run never fires at context start-up when it is large. */
    @Scheduled(fixedDelayString = "${surge.hold.sweep-interval:PT1S}",
            initialDelayString = "${surge.hold.sweep-interval:PT1S}")
    public void scheduledSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Hold expiry sweep failed", e);
        }
    }

    /** Returns the number of holds this call expired. */
    public int sweep() {
        Set<String> due = redis.opsForZSet().rangeByScore(RedisKeys.HOLD_EXPIRY, 0, clock.millis(), 0, BATCH);
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String member : due) {
            String[] parts = member.split(":", 3);
            long eventId = Long.parseLong(parts[0]);
            String[] seatIds = parts[2].split(",");

            List<String> keys = new ArrayList<>();
            keys.add(RedisKeys.HOLD_EXPIRY);
            keys.add(RedisKeys.sold(eventId));
            List<String> args = new ArrayList<>();
            args.add(member);
            for (String seatId : seatIds) {
                keys.add(RedisKeys.seat(eventId, Long.parseLong(seatId)));
                args.add(seatId);
            }

            List<?> result = redis.execute(sweepScript, keys, args.toArray());
            if (result == null || result.isEmpty()) {
                continue; // another instance or checkout got there first
            }
            count++;
            expired.increment();
            List<Long> freed = result.stream().skip(1).map(o -> Long.parseLong(String.valueOf(o))).toList();
            if (!freed.isEmpty()) {
                publisher.publish(eventId, freed, SeatState.AVAILABLE);
            }
        }
        return count;
    }
}
