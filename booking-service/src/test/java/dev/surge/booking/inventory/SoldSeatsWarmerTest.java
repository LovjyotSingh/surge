package dev.surge.booking.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SoldSeatsWarmerTest {

    @Autowired SoldSeatsWarmer warmer;
    @Autowired TestData testData;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void reset() {
        for (long e = 1; e <= 3; e++) {
            testData.resetEvent(e);
        }
    }

    private List<Long> firstSeatIds(long eventId, int n) {
        return jdbc.queryForList("SELECT id FROM seat WHERE event_id = ? ORDER BY row_label, number LIMIT ?",
                Long.class, eventId, n);
    }

    @Test
    void warmerRebuildsSoldSets() throws Exception {
        List<Long> ids = firstSeatIds(1, 3);
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id IN (?, ?)", ids.get(0), ids.get(1));

        warmer.run(new DefaultApplicationArguments());

        assertThat(redis.opsForSet().members("sold:1"))
                .isEqualTo(Set.of(String.valueOf(ids.get(0)), String.valueOf(ids.get(1))));
        assertThat(redis.hasKey("sold:2")).isFalse();
    }

    @Test
    void warmerDropsStaleMembersAndEmptySets() throws Exception {
        List<Long> ids = firstSeatIds(1, 3);
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id = ?", ids.get(0));
        String stale = String.valueOf(ids.get(2));
        redis.opsForSet().add("sold:1", stale);
        redis.opsForSet().add("sold:2", "999999");

        warmer.run(new DefaultApplicationArguments());

        assertThat(redis.opsForSet().members("sold:1")).containsExactly(String.valueOf(ids.get(0)));
        assertThat(redis.opsForSet().isMember("sold:1", stale)).isFalse();
        assertThat(redis.hasKey("sold:2")).isFalse();
    }
}
