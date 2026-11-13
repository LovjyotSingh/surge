package dev.surge.booking.stream;

import dev.surge.booking.event.SeatState;
import dev.surge.booking.hold.RedisKeys;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Publishes seat state changes to the per-event Redis pub/sub channel. */
@Component
public class SeatEventPublisher {

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public SeatEventPublisher(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void publish(long eventId, List<Long> seatIds, SeatState state) {
        String payload = json.writeValueAsString(new SeatStateChange(eventId, seatIds, state));
        redis.convertAndSend(RedisKeys.seatChannel(eventId), payload);
    }
}
