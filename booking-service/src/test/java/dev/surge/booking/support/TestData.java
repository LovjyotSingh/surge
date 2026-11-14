package dev.surge.booking.support;

import dev.surge.booking.inventory.InventoryCache;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test helper that restores an event to its freshly seeded state. Imported by @IntegrationTest. */
@TestComponent
public class TestData {

    private static final Instant DEFAULT_SALE_OPENS_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final InventoryCache inventoryCache;

    public TestData(JdbcTemplate jdbc, StringRedisTemplate redis, InventoryCache inventoryCache) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.inventoryCache = inventoryCache;
    }

    public void resetEvent(long eventId) {
        jdbc.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE event_id = ?)", eventId);
        jdbc.update("DELETE FROM orders WHERE event_id = ?", eventId);
        jdbc.update("UPDATE seat SET status = 'AVAILABLE' WHERE event_id = ?", eventId);
        setSaleOpensAt(eventId, DEFAULT_SALE_OPENS_AT);
        inventoryCache.evict(eventId);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    /** Removes an event created by a test (seats first) and drops its cached inventory. */
    public void deleteEvent(long eventId) {
        jdbc.update("DELETE FROM seat WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM event_ref WHERE id = ?", eventId);
        inventoryCache.evict(eventId);
    }

    public void clearOutbox() {
        jdbc.update("DELETE FROM outbox");
    }

    public void setSaleOpensAt(long eventId, Instant at) {
        jdbc.update("UPDATE event_ref SET sale_opens_at = ? WHERE id = ?", LocalDateTime.ofInstant(at, ZoneOffset.UTC), eventId);
        inventoryCache.evict(eventId);
    }
}
