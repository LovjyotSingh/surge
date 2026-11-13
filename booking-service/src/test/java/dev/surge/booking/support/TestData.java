package dev.surge.booking.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test helper that restores an event to its freshly seeded state. Register it with @Import(TestData.class). */
@TestComponent
public class TestData {

    private static final Instant DEFAULT_SALE_OPENS_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public TestData(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public void resetEvent(long eventId) {
        jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)", eventId);
        jdbc.update("DELETE FROM orders WHERE event_id = ?", eventId);
        jdbc.update("UPDATE seat SET status = 'AVAILABLE' WHERE event_id = ?", eventId);
        setSaleOpensAt(eventId, DEFAULT_SALE_OPENS_AT);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    public void setSaleOpensAt(long eventId, Instant at) {
        jdbc.update("UPDATE event_ref SET sale_opens_at = ? WHERE id = ?", LocalDateTime.ofInstant(at, ZoneOffset.UTC), eventId);
    }
}
