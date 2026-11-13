package dev.surge.booking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(TestData.class)
class TestDataTest {

    @Autowired TestData testData;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void resetEventRestoresSeededState() {
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id = 1");
        testData.setSaleOpensAt(3L, Instant.parse("2030-01-01T00:00:00Z"));
        redis.opsForValue().set("probe", "x");

        testData.resetEvent(1L);
        testData.resetEvent(3L);

        assertThat(jdbc.queryForObject("SELECT status FROM seat WHERE id = 1", String.class)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT DATE_FORMAT(sale_opens_at, '%Y-%m-%d %H:%i:%s') FROM event_ref WHERE id = 3", String.class))
                .isEqualTo("2026-01-01 00:00:00");
        assertThat(redis.hasKey("probe")).isFalse();
    }
}
