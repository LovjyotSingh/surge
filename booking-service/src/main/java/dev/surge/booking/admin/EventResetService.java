package dev.surge.booking.admin;

import dev.surge.booking.error.EventNotFoundException;
import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.event.SeatState;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.stream.SeatEventPublisher;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Restores an event to a freshly seeded state so a load test can be re-run. Outbox rows are left alone. */
@Service
public class EventResetService {

    private static final int CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final InventoryCache inventory;
    private final SeatEventPublisher publisher;
    private final EventRefRepository events;
    private final Clock clock;

    public EventResetService(JdbcTemplate jdbc, TransactionTemplate tx, StringRedisTemplate redis,
                             InventoryCache inventory, SeatEventPublisher publisher, EventRefRepository events,
                             Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.inventory = inventory;
        this.publisher = publisher;
        this.events = events;
        this.clock = clock;
    }

    public void reset(long eventId, int opensInSeconds) {
        if (!events.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        LocalDateTime opensAt = LocalDateTime.ofInstant(clock.instant().plusSeconds(opensInSeconds), ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)", eventId);
            jdbc.update("DELETE FROM orders WHERE event_id = ?", eventId);
            jdbc.update("UPDATE seat SET status = 'AVAILABLE' WHERE event_id = ?", eventId);
            jdbc.update("UPDATE event_ref SET sale_opens_at = ? WHERE id = ?", opensAt, eventId);
        });

        List<Long> seatIds = jdbc.queryForList("SELECT id FROM seat WHERE event_id = ? ORDER BY id", Long.class,
                eventId);
        List<String> keys = new ArrayList<>(seatIds.size() + 1);
        keys.add(RedisKeys.sold(eventId));
        seatIds.forEach(id -> keys.add(RedisKeys.seat(eventId, id)));
        deleteChunked(keys);

        clearHolds(eventId);
        clearUserHolds(eventId);

        inventory.evict(eventId);
        publisher.publish(eventId, seatIds, SeatState.AVAILABLE);
    }

    private void clearHolds(long eventId) {
        String prefix = eventId + ":";
        Set<String> members = redis.opsForZSet().range(RedisKeys.HOLD_EXPIRY, 0, -1);
        if (members == null || members.isEmpty()) {
            return;
        }
        List<String> mine = members.stream().filter(m -> m.startsWith(prefix)).toList();
        for (int i = 0; i < mine.size(); i += CHUNK) {
            List<String> chunk = mine.subList(i, Math.min(i + CHUNK, mine.size()));
            redis.opsForZSet().remove(RedisKeys.HOLD_EXPIRY, chunk.toArray());
        }
        List<String> holdKeys = new ArrayList<>(mine.size());
        for (String member : mine) {
            String[] parts = member.split(":", 3);
            if (parts.length >= 2) {
                holdKeys.add(RedisKeys.hold(parts[1]));
            }
        }
        deleteChunked(holdKeys);
    }

    private void clearUserHolds(long eventId) {
        List<String> batch = new ArrayList<>(CHUNK);
        ScanOptions options = ScanOptions.scanOptions().match("userhold:" + eventId + ":*").count(1000).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= CHUNK) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            redis.delete(batch);
        }
    }

    private void deleteChunked(List<String> keys) {
        for (int i = 0; i < keys.size(); i += CHUNK) {
            redis.delete(keys.subList(i, Math.min(i + CHUNK, keys.size())));
        }
    }
}
