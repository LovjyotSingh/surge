package dev.surge.booking.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
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
        testData.resetEvent(1L);
    }

    @Test
    void warmerRebuildsSoldSets() throws Exception {
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id IN (3, 4)");
        redis.delete("sold:1");

        warmer.run(new DefaultApplicationArguments());

        assertThat(redis.opsForSet().members("sold:1")).isEqualTo(Set.of("3", "4"));
        assertThat(redis.hasKey("sold:2")).isFalse();
    }
}
