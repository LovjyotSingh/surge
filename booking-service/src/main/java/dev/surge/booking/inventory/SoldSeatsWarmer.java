package dev.surge.booking.inventory;

import dev.surge.booking.event.EventRef;
import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.hold.RedisKeys;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds the Redis sold-seat sets from MySQL at startup. */
@Component
public class SoldSeatsWarmer implements ApplicationRunner {

    private final EventRefRepository events;
    private final SeatRepository seats;
    private final StringRedisTemplate redis;

    public SoldSeatsWarmer(EventRefRepository events, SeatRepository seats, StringRedisTemplate redis) {
        this.events = events;
        this.seats = seats;
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (EventRef event : events.findAll()) {
            String[] soldIds = seats.findByEventIdOrderByRowLabelAscNumberAsc(event.getId()).stream()
                    .filter(s -> s.getStatus() == SeatStatus.SOLD)
                    .map(Seat::getId).map(String::valueOf).toArray(String[]::new);
            if (soldIds.length > 0) {
                redis.opsForSet().add(RedisKeys.sold(event.getId()), soldIds);
            }
        }
    }
}
